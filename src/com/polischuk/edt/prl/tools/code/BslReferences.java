/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxyVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.findReferences.IReferenceFinder;
import org.eclipse.xtext.findReferences.TargetURIConverter;
import org.eclipse.xtext.findReferences.TargetURIs;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.resource.IReferenceDescription;
import org.eclipse.xtext.resource.IResourceDescription;
import org.eclipse.xtext.resource.IResourceDescriptions;
import org.eclipse.xtext.resource.IResourceServiceProvider;
import org.eclipse.xtext.util.concurrent.IUnitOfWork;

import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureAccess;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.SourceObjectLinkProvider;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.bsl.resource.DynamicFeatureAccessComputer;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.mcore.Environmental;
import com.polischuk.edt.prl.edt.EdtResourceSets;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.WorkspaceFiles;

/**
 * Спільна інфраструктура семантичного пошуку по коду BSL через Xtext-індекс EDT.
 *
 * Джерела даних:
 * - Xtext-індекс воркспейсу (builder state) — org.eclipse.xtext.ui.shared.Access
 *   .getIResourceDescriptions(): містить IReferenceDescription для міжмодульних
 *   посилань; читається з будь-якого потоку без блокувань;
 * - пошук посилань — org.eclipse.xtext.findReferences.IReferenceFinder з
 *   інжектора мови BSL (реєстр IResourceServiceProvider за розширенням "bsl");
 * - позиції в коді — вузлова модель Xtext (NodeModelUtils) завантаженого модуля.
 *
 * EDT записує в індекс посилання на BSL-метод з URI *вихідного* методу
 * (SourceObjectLinkProvider.getSourceUri), тому TargetURIs достатньо наповнити
 * EcoreUtil.getURI(method) — так само робить штатний Find References EDT.
 */
public final class BslReferences {

    /** Фіктивне ім'я ресурсу: розширення "bsl" вибирає мову BSL у реєстрі Xtext. */
    private static final String DUMMY_RESOURCE_NAME = "__mcp_refs__.bsl"; //$NON-NLS-1$

    private BslReferences() {
    }

    /** Провайдер Xtext-сервісів мови BSL з реєстру за розширенням "bsl". */
    public static IResourceServiceProvider bslProvider() {
        URI uri = URI.createURI(DUMMY_RESOURCE_NAME);
        IResourceServiceProvider provider =
                IResourceServiceProvider.Registry.INSTANCE.getResourceServiceProvider(uri);
        if (provider == null) {
            throw new IllegalStateException(
                    "Мова BSL не зареєстрована в Xtext-реєстрі (розширення 'bsl'). " //$NON-NLS-1$
                    + "Бандл com._1c.g5.v8.dt.bsl.ui ще не активований або відсутній."); //$NON-NLS-1$
        }
        return provider;
    }

    public static <T> T bslService(Class<T> type) {
        T service = bslProvider().get(type);
        if (service == null) {
            throw new IllegalStateException("Інжектор BSL не надає сервіс: " + type.getName()); //$NON-NLS-1$
        }
        return service;
    }

    /**
     * Xtext-індекс воркспейсу (builder state). Основний шлях — спільний інжектор
     * org.eclipse.xtext.ui.shared.Access; запасний — біндінг IResourceDescriptions
     * інжектора мови BSL.
     */
    public static IResourceDescriptions index() {
        try {
            com.google.inject.Provider<IResourceDescriptions> provider =
                    org.eclipse.xtext.ui.shared.Access.getIResourceDescriptions();
            IResourceDescriptions descriptions = provider == null ? null : provider.get();
            if (descriptions != null) {
                return descriptions;
            }
        } catch (RuntimeException | LinkageError e) {
            // спільний інжектор недоступний — пробуємо біндінг мови BSL
        }
        IResourceDescriptions fallback = bslProvider().get(IResourceDescriptions.class);
        if (fallback == null) {
            throw new IllegalStateException(
                    "Xtext-індекс недоступний: ані Access.getIResourceDescriptions(), " //$NON-NLS-1$
                    + "ані біндінг IResourceDescriptions мови BSL."); //$NON-NLS-1$
        }
        return fallback;
    }

    // ------------------------------------------------------------------
    // Завантаження модулів
    // ------------------------------------------------------------------

    /** Platform-URI файлу модуля відносно проєкту. */
    public static URI moduleUri(IProject project, String projectRelativePath) {
        IFile file = WorkspaceFiles.file(project, projectRelativePath);
        if (!"bsl".equalsIgnoreCase(file.getFileExtension())) { //$NON-NLS-1$
            throw new IllegalArgumentException("Файл не є модулем BSL: " + projectRelativePath); //$NON-NLS-1$
        }
        return URI.createPlatformResourceURI(
                project.getName() + "/" + file.getProjectRelativePath().toString(), true); //$NON-NLS-1$
    }

    /** Завантажує модуль BSL як Xtext-ресурс у ResourceSet проєкту. */
    public static Module loadModule(ResourceAccess access, IProject project, String projectRelativePath) {
        URI uri = moduleUri(project, projectRelativePath);
        Resource resource = access.resourceSet(project).getResource(uri, true);
        if (resource == null || resource.getContents().isEmpty()
                || !(resource.getContents().get(0) instanceof Module module)) {
            throw new IllegalStateException("Не вдалося завантажити модуль BSL: " + uri); //$NON-NLS-1$
        }
        return module;
    }

    /** Метод модуля за ім'ям (без регістру). */
    public static Method findMethod(Module module, String methodName) {
        List<String> available = new ArrayList<>();
        for (Method method : module.allMethods()) {
            if (methodName.equalsIgnoreCase(method.getName())) {
                return method;
            }
            available.add(method.getName());
        }
        throw new IllegalArgumentException("Метод не знайдено: " + methodName //$NON-NLS-1$
                + ". Методи модуля: " + String.join(", ", available)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ------------------------------------------------------------------
    // Пошук посилань через IReferenceFinder
    // ------------------------------------------------------------------

    /**
     * Усі місця, що посилаються на targetUris: повертає URI EObject-джерел
     * (наприклад, DynamicFeatureAccess у модулі, що викликає метод).
     *
     * <p>Якщо в {@code access} задано область пошуку ({@link ResourceAccess#restrictTo}),
     * скануються лише її ресурси; інакше — увесь індекс воркспейсу. Повний скан на
     * типовій конфігурації в 17 проєктів коштує ~1 хвилину на виклик, тож інструменти
     * звужують область до проєкту цілі та проєктів, що від нього залежать.
     */
    public static LinkedHashSet<URI> findReferenceSources(List<URI> targetUris, ResourceAccess access) {
        TargetURIs targets = bslService(TargetURIConverter.class).fromIterable(targetUris);
        IReferenceFinder finder = bslService(IReferenceFinder.class);
        LinkedHashSet<URI> sources = new LinkedHashSet<>();
        IReferenceFinder.Acceptor acceptor = new IReferenceFinder.Acceptor() {
            @Override
            public void accept(EObject source, URI sourceUri, EReference reference, int index,
                    EObject targetOrProxy, URI targetUri) {
                if (sourceUri != null) {
                    sources.add(sourceUri);
                }
            }

            @Override
            public void accept(IReferenceDescription description) {
                if (description.getSourceEObjectUri() != null) {
                    sources.add(description.getSourceEObjectUri());
                }
            }
        };

        Set<URI> scope = access.scopeUris();
        if (scope == null) {
            finder.findAllReferences(targets, access, index(), acceptor, new NullProgressMonitor());
        } else {
            // ресурси самої цілі — щоб не загубити посилання всередині них
            Set<URI> scanned = new LinkedHashSet<>(scope);
            scanned.addAll(targets.getTargetResourceURIs());
            finder.findReferences(targets, scanned, access, index(), acceptor, new NullProgressMonitor());
        }
        return sources;
    }

    // ------------------------------------------------------------------
    // Область пошуку
    // ------------------------------------------------------------------

    /**
     * Імена проєктів, у яких може бути посилання на об'єкт із {@code project}:
     * сам проєкт плюс ті, що від нього залежать (розширення конфігурації тощо).
     * Порожня множина — не вдалося визначити, шукати треба по всьому воркспейсу.
     */
    public static Set<String> dependencyClosure(IProject project) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.add(project.getName());
        try {
            IDtProjectManager dtProjectManager = EdtServices.get(IDtProjectManager.class);
            if (dtProjectManager == null) {
                return Set.of();
            }
            IDtProject dtProject = dtProjectManager.getDtProject(project);
            if (dtProject == null) {
                return Set.of();
            }
            for (IDtProject dependent : dtProjectManager.getDependentProjects(dtProject)) {
                IProject dependentProject = dtProjectManager.getProject(dependent);
                if (dependentProject != null) {
                    names.add(dependentProject.getName());
                }
            }
            return names;
        } catch (Throwable e) { // API дрейфнув — чесніше просканувати все
            return Set.of();
        }
    }

    /**
     * URI ресурсів, серед яких шукати посилання, для вказаних проєктів.
     *
     * <p>Береться з дерева файлів (модулі *.bsl), а не з перебору всього індексу:
     * перебір індексу воркспейсу коштував ~4.5 с на виклик, тобто більше за сам пошук.
     * Якщо обхід нічого не дав — відкат на перебір індексу.
     */
    public static Set<URI> resourceUrisOfProjects(Set<String> projectNames) {
        Set<URI> uris = new LinkedHashSet<>();
        for (String projectName : projectNames) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.isOpen()) {
                continue;
            }
            try {
                project.accept((IResourceProxyVisitor) proxy -> {
                    if (proxy.getType() != IResource.FILE) {
                        return true;
                    }
                    if (proxy.getName().toLowerCase(Locale.ROOT).endsWith(".bsl")) { //$NON-NLS-1$
                        IPath fullPath = proxy.requestFullPath();
                        if (fullPath != null) {
                            uris.add(URI.createPlatformResourceURI(
                                    fullPath.makeRelative().toString(), true));
                        }
                    }
                    return false;
                }, IResource.NONE);
            } catch (CoreException e) {
                // проєкт закрився під час обходу — рахуємо, що обхід не вдався
            }
        }
        return uris.isEmpty() ? resourceUrisFromIndex(projectNames) : uris;
    }

    /** Запасний шлях: URI ресурсів індексу, що належать указаним проєктам. */
    private static Set<URI> resourceUrisFromIndex(Set<String> projectNames) {
        Set<URI> uris = new LinkedHashSet<>();
        for (IResourceDescription description : index().getAllResourceDescriptions()) {
            URI uri = description.getURI();
            if (uri != null && uri.isPlatformResource() && uri.segmentCount() > 1
                    && projectNames.contains(URI.decode(uri.segment(1)))) {
                uris.add(uri);
            }
        }
        return uris;
    }

    /** Виклики методу methodName усередині самого модуля (StaticFeatureAccess). */
    public static List<Invocation> localInvocations(Module module, String methodName) {
        List<Invocation> result = new ArrayList<>();
        for (Invocation invocation : EcoreUtil2.getAllContentsOfType(module, Invocation.class)) {
            FeatureAccess methodAccess = invocation.getMethodAccess();
            if (methodAccess instanceof StaticFeatureAccess
                    && methodAccess.getName() != null
                    && methodAccess.getName().equalsIgnoreCase(methodName)) {
                result.add(invocation);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Розв'язання цілі виклику (для вихідної ієрархії)
    // ------------------------------------------------------------------

    /**
     * Ціль виклику Invocation:
     * kind = "local" (метод цього ж модуля), "module" (метод іншого модуля,
     * methodUri — URI вихідного bsl-методу), "platform" (метод платформи/контексту),
     * "global" (глобальна функція платформи), "unresolved".
     */
    public record Callee(String name, String kind, URI methodUri) {
    }

    public static Callee resolveCallee(Invocation invocation, Module module,
            DynamicFeatureAccessComputer computer) {
        FeatureAccess methodAccess = invocation.getMethodAccess();
        if (methodAccess instanceof StaticFeatureAccess) {
            String name = methodAccess.getName();
            if (name == null) {
                return null;
            }
            for (Method method : module.allMethods()) {
                if (name.equalsIgnoreCase(method.getName())) {
                    return new Callee(method.getName(), "local", //$NON-NLS-1$
                            org.eclipse.emf.ecore.util.EcoreUtil.getURI(method));
                }
            }
            return new Callee(name, "global", null); //$NON-NLS-1$
        }
        if (methodAccess instanceof DynamicFeatureAccess dynamicAccess) {
            String name = methodAccess.getName();
            try {
                Environmental environmental =
                        EcoreUtil2.getContainerOfType(dynamicAccess, Environmental.class);
                if (environmental != null) {
                    List<FeatureEntry> entries =
                            computer.getLastObject(dynamicAccess, environmental.environments(), true);
                    if (entries != null && !entries.isEmpty()) {
                        // штатний Find References EDT бере останній FeatureEntry
                        EObject feature = entries.get(entries.size() - 1).getFeature();
                        if (feature instanceof SourceObjectLinkProvider link && link.getSourceUri() != null) {
                            return new Callee(name, "module", link.getSourceUri()); //$NON-NLS-1$
                        }
                        if (feature instanceof com._1c.g5.v8.dt.mcore.Method
                                || feature instanceof com._1c.g5.v8.dt.mcore.Property) {
                            return new Callee(name, "platform", null); //$NON-NLS-1$
                        }
                    }
                }
            } catch (RuntimeException e) {
                // обчислення типів не вдалося — залишаємо unresolved
            }
            return new Callee(name, "unresolved", null); //$NON-NLS-1$
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Позиції в коді
    // ------------------------------------------------------------------

    /** Місце в коді: проєкт, шлях модуля, рядок (1-based), текст рядка, метод-контейнер. */
    public record Location(String project, String path, int line, String snippet, String method) {
    }

    /** Рядок початку вузла AST (1-based); 0 — якщо вузол недоступний. */
    public static int nodeStartLine(EObject eObject) {
        ICompositeNode node = NodeModelUtils.findActualNodeFor(eObject);
        return node == null ? 0 : node.getStartLine();
    }

    /**
     * Розташування EObject-джерела посилання: завантажує ресурс, знаходить вузол
     * AST і метод-контейнер. linesCache — кеш рядків файлів між викликами.
     */
    public static Location locate(URI sourceUri, ResourceAccess access, Map<String, String[]> linesCache) {
        if (!sourceUri.isPlatformResource()) {
            return new Location(null, sourceUri.trimFragment().toString(), 0, "", null); //$NON-NLS-1$
        }
        String projectName = URI.decode(sourceUri.segment(1));
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 2; i < sourceUri.segmentCount(); i++) {
            if (pathBuilder.length() > 0) {
                pathBuilder.append('/');
            }
            pathBuilder.append(URI.decode(sourceUri.segment(i)));
        }
        String path = pathBuilder.toString();

        int line = 0;
        String methodName = null;
        try {
            ResourceSet resourceSet = access.resourceSet(sourceUri);
            Resource resource = resourceSet.getResource(sourceUri.trimFragment(), true);
            EObject eObject = sourceUri.fragment() == null ? null
                    : resource.getEObject(sourceUri.fragment());
            if (eObject != null) {
                line = nodeStartLine(eObject);
                Method container = EcoreUtil2.getContainerOfType(eObject, Method.class);
                if (container != null) {
                    methodName = container.getName();
                }
            }
        } catch (RuntimeException e) {
            // ресурс не завантажився — повертаємо позицію без рядка
        }
        String snippet = line > 0 ? lineText(projectName, path, line, linesCache) : ""; //$NON-NLS-1$
        return new Location(projectName, path, line, snippet, methodName);
    }

    /** Текст рядка файлу (1-based) з кешем прочитаних файлів. */
    public static String lineText(String projectName, String path, int line, Map<String, String[]> linesCache) {
        String key = projectName + "/" + path; //$NON-NLS-1$
        String[] lines = linesCache.computeIfAbsent(key, k -> {
            try {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                return WorkspaceFiles.read(project, path).split("\n", -1); //$NON-NLS-1$
            } catch (RuntimeException e) {
                return new String[0];
            }
        });
        if (line < 1 || line > lines.length) {
            return ""; //$NON-NLS-1$
        }
        return lines[line - 1].strip();
    }

    // ------------------------------------------------------------------
    // Доступ до ресурсів для IReferenceFinder
    // ------------------------------------------------------------------

    /**
     * Read-only доступ до ресурсів: ResourceSet на проєкт (IResourceSetProvider
     * інжектора BSL), кешується на час одного виклику інструмента. Використовується
     * і фіндером (локальні посилання в модулі-цілі), і розв'язанням позицій.
     */
    public static final class ResourceAccess implements IReferenceFinder.IResourceAccess {

        private final Map<String, ResourceSet> resourceSets = new HashMap<>();
        private ResourceSet defaultSet;
        private Set<URI> scopeUris;

        /** Обмежує пошук посилань указаними ресурсами; null — увесь індекс. */
        public void restrictTo(Set<URI> uris) {
            this.scopeUris = uris;
        }

        public Set<URI> scopeUris() {
            return scopeUris;
        }

        public ResourceSet resourceSet(IProject project) {
            ResourceSet result = resourceSets.computeIfAbsent(project.getName().toLowerCase(Locale.ROOT),
                    key -> {
                        ResourceSet set = EdtResourceSets.forProjectPreferLanguage(project, bslProvider());
                        if (set == null) {
                            throw new IllegalStateException(
                                    "Не вдалося створити ResourceSet для проєкту " + project.getName() //$NON-NLS-1$
                                    + " (ані через інжектор BSL, ані BM-aware)."); //$NON-NLS-1$
                        }
                        return set;
                    });
            if (defaultSet == null) {
                defaultSet = result;
            }
            return result;
        }

        public ResourceSet resourceSet(URI uri) {
            if (uri.isPlatformResource()) {
                IProject project = ResourcesPlugin.getWorkspace().getRoot()
                        .getProject(URI.decode(uri.segment(1)));
                if (project.isOpen()) {
                    return resourceSet(project);
                }
            }
            if (defaultSet == null) {
                throw new IllegalStateException("Немає ResourceSet для URI: " + uri); //$NON-NLS-1$
            }
            return defaultSet;
        }

        @Override
        public <R> R readOnly(URI targetUri, IUnitOfWork<R, ResourceSet> work) {
            try {
                return work.exec(resourceSet(targetUri));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Помилка read-only доступу до " + targetUri + ": " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }
}
