/*
 * MCP:PRL Server — плагін для 1С:EDT (Model Context Protocol).
 * Copyright (c) 2026 Polischuk. Усі права захищені. All rights reserved.
 *
 * Пропрієтарне і конфіденційне програмне забезпечення.
 * Будь-яке копіювання, розповсюдження, декомпіляція чи модифікація
 * без письмового дозволу автора заборонені.
 */
package com.polischuk.edt.prl.tools.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import com._1c.g5.v8.dt.core.platform.IResourceLookup;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.PlainEObjectMarker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.polischuk.edt.prl.edt.EdtServices;
import com.polischuk.edt.prl.edt.V8Access;
import com.polischuk.edt.prl.tools.McpTool;

/**
 * Помилки та попередження валідації EDT по проєкту.
 * <p>
 * Два канали маркерів:
 * <ul>
 * <li>EDT-нативні (IMarkerManager) — усі перевірки фреймворку checks: якість коду БСП,
 * семантика BSL, форми, метадані, запозичення розширень. Саме тут живе ~99% того, що
 * EDT показує у вкладці «Проблеми»;</li>
 * <li>Eclipse IMarker.PROBLEM — синтаксис Xtext і збирачі (у великих конфігураціях їх
 * одиниці).</li>
 * </ul>
 * Дублікати (той самий файл+рядок+текст) схлопуються на користь EDT-маркера, бо в нього є checkId.
 */
public final class GetValidationErrorsTool implements McpTool {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private static final String SOURCE_EDT = "edt"; //$NON-NLS-1$
    private static final String SOURCE_ECLIPSE = "eclipse"; //$NON-NLS-1$

    private static final String SEV_ERROR = "error"; //$NON-NLS-1$
    private static final String SEV_WARNING = "warning"; //$NON-NLS-1$
    private static final String SEV_INFO = "info"; //$NON-NLS-1$

    @Override
    public String name() {
        return "get_validation_errors"; //$NON-NLS-1$
    }

    @Override
    public String description() {
        return "Помилки/попередження валідації EDT по проєкту — обидва канали: перевірки EDT (checkId, як у вкладці " //$NON-NLS-1$
                + "«Проблеми») і Eclipse-маркери синтаксису. severity: error|warning|info (без нього — всі); " //$NON-NLS-1$
                + "pathFilter — підрядок шляху файлу; checkId — фільтр за кодом перевірки (SU31); " //$NON-NLS-1$
                + "source: all|edt|eclipse; offset/limit — пагінація. Помилки йдуть першими. " //$NON-NLS-1$
                + "У типових конфігураціях — сотні тисяч маркерів: звужуйте pathFilter/severity."; //$NON-NLS-1$
    }

    @Override
    public JsonObject inputSchema() {
        return JsonParser.parseString("""
                {"type":"object","properties":{
                  "project":{"type":"string","description":"Ім'я проєкту EDT (необов'язково, якщо проєкт один)"},
                  "severity":{"type":"string","enum":["error","warning","info"],"description":"Рівень (EDT: ERRORS/BLOCKER/CRITICAL/MAJOR→error, MINOR→warning, TRIVIAL→info); без нього — всі"},
                  "pathFilter":{"type":"string","description":"Підрядок шляху файлу відносно проєкту, без регістру (напр. Forms/Форма/Module.bsl)"},
                  "checkId":{"type":"string","description":"Код перевірки EDT (напр. SU31); лише для каналу edt"},
                  "source":{"type":"string","enum":["all","edt","eclipse"],"default":"all"},
                  "offset":{"type":"integer","default":0,"minimum":0},
                  "limit":{"type":"integer","default":200,"minimum":1,"maximum":1000}
                }}""").getAsJsonObject(); //$NON-NLS-1$
    }

    @Override
    public JsonElement execute(JsonObject arguments) throws CoreException {
        String projectName = arguments.has("project") ? arguments.get("project").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String severityFilter = optionalLower(arguments, "severity"); //$NON-NLS-1$
        String pathFilter = optionalLower(arguments, "pathFilter"); //$NON-NLS-1$
        String checkIdFilter = optionalLower(arguments, "checkId"); //$NON-NLS-1$
        String sourceFilter = optionalLower(arguments, "source"); //$NON-NLS-1$
        if (sourceFilter == null || "all".equals(sourceFilter)) { //$NON-NLS-1$
            sourceFilter = null;
        }
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : DEFAULT_LIMIT; //$NON-NLS-1$ //$NON-NLS-2$
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int offset = arguments.has("offset") ? Math.max(0, arguments.get("offset").getAsInt()) : 0; //$NON-NLS-1$ //$NON-NLS-2$

        IProject project = V8Access.resolveEclipseProject(projectName);

        // ключ дедуплікації → запис; EDT-канал заповнюється першим і має пріоритет
        Map<String, Entry> entries = new LinkedHashMap<>();
        JsonObject channels = new JsonObject();

        int edtRaw = 0;
        boolean edtAvailable = false;
        if (sourceFilter == null || SOURCE_EDT.equals(sourceFilter)) {
            try {
                IMarkerManager markerManager = EdtServices.get(IMarkerManager.class);
                if (markerManager != null) {
                    edtAvailable = true;
                    PathResolver resolver = new PathResolver(project);
                    try (Stream<Marker> stream = markerManager.markers(MarkerFilter.createProjectFilter(project))) {
                        for (Marker marker : (Iterable<Marker>) stream::iterator) {
                            edtRaw++;
                            Entry entry = fromEdtMarker(marker, project, resolver);
                            if (entry != null) {
                                entries.putIfAbsent(entry.key(), entry);
                            }
                        }
                    }
                } else {
                    channels.addProperty("edtChannelError", //$NON-NLS-1$
                            "IMarkerManager не зареєстрований у реєстрі OSGi"); //$NON-NLS-1$
                }
            } catch (Throwable e) { // LinkageError при дрейфі API EDT теж сюди
                channels.addProperty("edtChannelError", e.getClass().getSimpleName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        int eclipseRaw = 0;
        int deduped = 0;
        if (sourceFilter == null || SOURCE_ECLIPSE.equals(sourceFilter)) {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            for (IMarker marker : markers) {
                eclipseRaw++;
                Entry entry = fromEclipseMarker(marker);
                if (entries.containsKey(entry.key())) {
                    deduped++;
                } else {
                    entries.put(entry.key(), entry);
                }
            }
        }
        channels.addProperty("edtChannelAvailable", edtAvailable); //$NON-NLS-1$
        channels.addProperty("edtMarkers", edtRaw); //$NON-NLS-1$
        channels.addProperty("eclipseMarkers", eclipseRaw); //$NON-NLS-1$
        channels.addProperty("dedupedDuplicates", deduped); //$NON-NLS-1$

        // підрахунок по рівнях — по всьому проєкту (до фільтрів), як і раніше
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : entries.values()) {
            switch (entry.severity) {
            case SEV_ERROR -> errors++;
            case SEV_WARNING -> warnings++;
            default -> infos++;
            }
            if (severityFilter != null && !severityFilter.equals(entry.severity)) {
                continue;
            }
            if (checkIdFilter != null
                    && (entry.checkId == null || !checkIdFilter.equals(entry.checkId.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            if (pathFilter != null) {
                String path = entry.resolvedPath();
                if (path == null || !path.toLowerCase(Locale.ROOT).contains(pathFilter)) {
                    continue;
                }
            }
            filtered.add(entry);
        }
        // помилки першими, далі попередження, далі інфо; порядок усередині рівня стабільний
        filtered.sort((a, b) -> Integer.compare(a.rank(), b.rank()));

        JsonArray items = new JsonArray();
        int end = Math.min(filtered.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            items.add(filtered.get(i).toJson());
        }

        JsonObject result = new JsonObject();
        result.addProperty("project", project.getName()); //$NON-NLS-1$
        result.addProperty("errorCount", errors); //$NON-NLS-1$
        result.addProperty("warningCount", warnings); //$NON-NLS-1$
        result.addProperty("infoCount", infos); //$NON-NLS-1$
        result.addProperty("matched", filtered.size()); //$NON-NLS-1$
        result.addProperty("offset", offset); //$NON-NLS-1$
        result.addProperty("returned", items.size()); //$NON-NLS-1$
        result.addProperty("hasMore", end < filtered.size()); //$NON-NLS-1$
        result.add("channels", channels); //$NON-NLS-1$
        result.add("problems", items); //$NON-NLS-1$
        if (!edtAvailable && (sourceFilter == null || SOURCE_EDT.equals(sourceFilter))) {
            result.addProperty("hint", //$NON-NLS-1$
                    "Канал перевірок EDT недоступний — показані лише Eclipse-маркери (синтаксис). " //$NON-NLS-1$
                            + "Див. channels.edtChannelError і show_edt_version.edtServices."); //$NON-NLS-1$
        }
        return result;
    }

    private static String optionalLower(JsonObject arguments, String key) {
        if (!arguments.has(key) || arguments.get(key).isJsonNull()) {
            return null;
        }
        String value = arguments.get(key).getAsString().trim();
        return value.isEmpty() ? null : value.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- канал EDT

    private static Entry fromEdtMarker(Marker marker, IProject project, PathResolver resolver) {
        String nativeSeverity = marker.getSeverity() == null ? null : marker.getSeverity().name();
        String severity = mapSeverity(nativeSeverity);
        if (severity == null) {
            return null; // NONE — службові маркери індексу, у «Проблемах» їх немає
        }
        Entry entry = new Entry(SOURCE_EDT, severity, marker.getMessage());
        entry.severityNative = nativeSeverity;
        entry.checkId = marker.getCheckId();
        entry.marker = marker;
        entry.resolver = resolver;

        // BSL-маркери: ідентифікатор об'єкта — платформний шлях "/proj/src/.../Module.bsl"
        Object objectId = marker.getMarkerObjectId();
        if (objectId instanceof String text && text.startsWith("/")) { //$NON-NLS-1$
            entry.path = projectRelative(text, project);
        } else if (marker instanceof PlainEObjectMarker plain && plain.getURI() != null
                && plain.getURI().isPlatformResource()) {
            entry.path = projectRelative(plain.getURI().toPlatformString(true), project);
        }
        // інакше BM-об'єкт метаданих — шлях резолвиться ліниво (лише для сторінки/pathFilter)

        Map<String, String> extra = marker.getExtraInfo();
        if (extra != null) {
            entry.line = parseInt(extra.get("line")); //$NON-NLS-1$
            entry.offset = parseInt(extra.get("offset")); //$NON-NLS-1$
            entry.length = parseInt(extra.get("length")); //$NON-NLS-1$
        }
        return entry;
    }

    /** ERRORS/BLOCKER/CRITICAL/MAJOR → error; MINOR → warning; TRIVIAL → info; NONE → null. */
    private static String mapSeverity(String nativeSeverity) {
        if (nativeSeverity == null) {
            return null;
        }
        return switch (nativeSeverity) {
        case "ERRORS", "BLOCKER", "CRITICAL", "MAJOR" -> SEV_ERROR; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        case "MINOR" -> SEV_WARNING; //$NON-NLS-1$
        case "TRIVIAL" -> SEV_INFO; //$NON-NLS-1$
        default -> null;
        };
    }

    private static String projectRelative(String platformPath, IProject project) {
        String prefix = "/" + project.getName() + "/"; //$NON-NLS-1$ //$NON-NLS-2$
        if (platformPath.startsWith(prefix)) {
            return platformPath.substring(prefix.length());
        }
        return platformPath.startsWith("/") ? platformPath.substring(1) : platformPath; //$NON-NLS-1$
    }

    private static int parseInt(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ канал Eclipse

    private static Entry fromEclipseMarker(IMarker marker) {
        int severityCode = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
        String severity = severityCode == IMarker.SEVERITY_ERROR ? SEV_ERROR
                : severityCode == IMarker.SEVERITY_WARNING ? SEV_WARNING : SEV_INFO;
        Entry entry = new Entry(SOURCE_ECLIPSE, severity, marker.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$
        IResource resource = marker.getResource();
        entry.path = resource == null ? "" : resource.getProjectRelativePath().toString(); //$NON-NLS-1$
        entry.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
        entry.offset = marker.getAttribute(IMarker.CHAR_START, -1);
        int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
        entry.length = entry.offset >= 0 && charEnd >= entry.offset ? charEnd - entry.offset : -1;
        return entry;
    }

    // ------------------------------------------------------------------ модель

    /** Шлях файлу BM-об'єкта (.mdo/.form/.dcs) за маркером; кеш по верхньому об'єкту. */
    private static final class PathResolver {
        private final IProject project;
        private final Map<Object, String> cache = new HashMap<>();
        private IResourceLookup lookup;
        private boolean lookupChecked;

        PathResolver(IProject project) {
            this.project = project;
        }

        String resolve(Marker marker) {
            Object topId = marker.getTopObjectId();
            Object cacheKey = topId != null ? topId : marker.getMarkerObjectId();
            if (cacheKey != null && cache.containsKey(cacheKey)) {
                return cache.get(cacheKey);
            }
            String path;
            try {
                path = marker.provideObject(this::pathOf);
            } catch (Throwable e) { // об'єкт уже видалений / модель зайнята — маркер без шляху
                path = null;
            }
            if (cacheKey != null) {
                cache.put(cacheKey, path);
            }
            return path;
        }

        private String pathOf(EObject object) {
            if (object == null) {
                return null;
            }
            EObject root = EcoreUtil.getRootContainer(object);
            IResourceLookup resourceLookup = resourceLookup();
            if (resourceLookup != null) {
                IFile file = resourceLookup.getPlatformResource(root);
                if (file != null) {
                    return file.getProjectRelativePath().toString();
                }
            }
            URI uri = EcoreUtil.getURI(root);
            if (uri != null && uri.isPlatformResource()) {
                IFile file = ResourcesPlugin.getWorkspace().getRoot()
                        .getFile(new Path(uri.toPlatformString(true)));
                return file.getProject().equals(project) ? file.getProjectRelativePath().toString()
                        : file.getFullPath().toString();
            }
            return null;
        }

        private IResourceLookup resourceLookup() {
            if (!lookupChecked) {
                lookupChecked = true;
                try {
                    lookup = EdtServices.get(IResourceLookup.class);
                } catch (Throwable e) {
                    lookup = null;
                }
            }
            return lookup;
        }
    }

    private static final class Entry {
        final String source;
        final String severity;
        final String message;
        String severityNative;
        String checkId;
        String path;
        int line = -1;
        int offset = -1;
        int length = -1;
        Marker marker;
        PathResolver resolver;
        private boolean pathResolved;

        Entry(String source, String severity, String message) {
            this.source = source;
            this.severity = severity;
            this.message = message == null ? "" : message; //$NON-NLS-1$
        }

        int rank() {
            return SEV_ERROR.equals(severity) ? 0 : SEV_WARNING.equals(severity) ? 1 : 2;
        }

        /** Шлях; для BM-маркерів — лінива резолюція через модель (один раз). */
        String resolvedPath() {
            if (path == null && !pathResolved && marker != null && resolver != null) {
                pathResolved = true;
                path = resolver.resolve(marker);
            }
            return path;
        }

        /** Ключ дедуплікації між каналами: шлях + рядок + текст. */
        String key() {
            String keyPath = path != null ? path
                    : marker != null && marker.getMarkerObjectId() != null ? String.valueOf(marker.getMarkerObjectId())
                            : ""; //$NON-NLS-1$
            return keyPath + '|' + line + '|' + message;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("severity", severity); //$NON-NLS-1$
            if (severityNative != null) {
                json.addProperty("severityNative", severityNative); //$NON-NLS-1$
            }
            json.addProperty("source", source); //$NON-NLS-1$
            String resolved = resolvedPath();
            json.addProperty("path", resolved == null ? "" : resolved); //$NON-NLS-1$ //$NON-NLS-2$
            if (line > 0) {
                json.addProperty("line", line); //$NON-NLS-1$
            }
            if (offset >= 0) {
                json.addProperty("offset", offset); //$NON-NLS-1$
            }
            if (length >= 0) {
                json.addProperty("length", length); //$NON-NLS-1$
            }
            json.addProperty("message", message); //$NON-NLS-1$
            if (checkId != null && !checkId.isEmpty()) {
                json.addProperty("checkId", checkId); //$NON-NLS-1$
            }
            if (marker != null) {
                try {
                    String location = marker.getLocation();
                    if (location != null && !location.isEmpty()) {
                        json.addProperty("location", location); //$NON-NLS-1$
                    }
                    if (resolved == null || !resolved.endsWith(".bsl")) { //$NON-NLS-1$
                        String object = marker.getObjectPresentation();
                        if (object != null && !object.isEmpty()) {
                            json.addProperty("object", object); //$NON-NLS-1$
                        }
                    }
                } catch (Throwable e) {
                    // презентація потребує BM-об'єкта; без неї маркер усе одно корисний
                }
                Object markerObjectId = marker.getMarkerObjectId();
                if (markerObjectId != null) {
                    json.addProperty("markerObjectId", String.valueOf(markerObjectId)); //$NON-NLS-1$
                }
            }
            return json;
        }
    }
}
