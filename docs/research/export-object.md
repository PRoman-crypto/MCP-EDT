# export_object: збирання .epf/.erf з вихідників EDT

Дослідження API EDT 2025.2.6 (усі сигнатури — з `javap` по jar-ах
`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\plugins`).

## Висновок

Повноцінна реалізація можлива і зроблена: `src\com\polischuk\edt\prl\tools\export\ExportObjectTool.java`.
Головний сервіс — **`IExternalObjectDumper`** з бандла
`com._1c.g5.v8.dt.platform.services.core`. Він реєструється в OSGi-реєстрі
активатором бандла (`PlatformServicesCore` → `InjectorAwareServiceRegistrator.service(...).registerInjected()`),
тому доступний через наш `EdtServices.require(...)`.

Важливе обмеження: EDT не збирає бінарник сам — під капотом об'єкт експортується
у XML у тимчасовий каталог і конвертується в .epf/.erf **товстим клієнтом 1С**
(`IThickClientLauncher.convertXmlExternalToBinary`) з інформаційною базою,
пов'язаною з проєктом. Тобто для роботи інструмента потрібні:

1. проєкт зовнішніх обробок/звітів (`IExternalObjectProject`) у workspace;
2. пов'язана з ним (або з батьківською конфігурацією) інформаційна база;
3. встановлена платформа 1С відповідної версії.

У поточному workspace (лише конфігурація retail) інструмент чемно повертає
помилку «У workspace немає проєктів зовнішніх обробок/звітів».

## Знайдені класи і сигнатури (javap)

### com._1c.g5.v8.dt.platform.services.core_21.0.0.v202605050943.jar

Пакет `com._1c.g5.v8.dt.platform.services.core.dump` (Export-Package, version 4.0.0):

```java
public interface IExternalObjectDumper {
    void dump(org.eclipse.core.resources.IProject, org.eclipse.emf.ecore.EObject,
              java.nio.file.Path, org.eclipse.core.runtime.IProgressMonitor)
        throws org.eclipse.core.runtime.CoreException;
}

public interface IExternalObjectDumpSupport {
    void setDumpStorePath(IProject, Path) throws CoreException;
    Path getDumpStorePath(IProject);
    boolean isEnabled(IProject);
    void setEnabled(IProject, boolean);
    Path getDump(IProject, EObject, boolean, IProgressMonitor) throws CoreException;
    void updateDump(IProject, EObject, IProgressMonitor) throws CoreException;
    IStatus validateDumpGeneration(IProject);
    IStatus validateDumpGenerationForParent(IProject);
}

public interface IExternalObjectRestorer {  // імпорт .epf → проєкт (зворотна операція, на майбутнє)
    void restore(IProject, Path, IProgressMonitor) throws CoreException;
    void restore(String, com._1c.g5.v8.dt.platform.version.Version, IProject, Path, IProgressMonitor) throws CoreException;
    void restore(IProject, Path, Path, IProgressMonitor) throws CoreException;
}
```

Механіка `ExternalObjectDumper.dump` (з байткоду): `IExportOperationFactory.createExportOperation`
→ XML у temp-каталог (`FileUtil.createTempDirectory("xml-ext-obj-")`) →
`AbstractExternalObjectOperator.getInfobase(project, monitor)` (пов'язана ІБ, `InfobaseReference`) →
`getExecutionInstallation` (інсталяція платформи через `IResolvableRuntimeInstallationManager`) →
компонент `com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient` →
`IThickClientLauncher.convertXmlExternalToBinary(ILaunchableRuntimeComponent, InfobaseReference,
RuntimeExecutionArguments, Path, Path)`.

Реєстрація в OSGi — активатор `com._1c.g5.v8.dt.internal.platform.services.core.PlatformServicesCore`:
`registrator.service(IExternalObjectDumper.class).registerInjected()` і так само для
`IExternalObjectDumpSupport` — отже `EdtServices.require(...)` їх знаходить.

### com._1c.g5.v8.dt.core_26.0.1.v202605050943.jar

Пакет `com._1c.g5.v8.dt.core.platform` (вже в Import-Package):

```java
public interface IExternalObjectProject extends IDependentProject {
    MdObject getExternalObject(String, org.eclipse.emf.ecore.EClass);
    Collection<MdObject> getExternalObjects();
    Collection<MdObject> getExternalObjects(Predicate<MdObject>);
    <T extends MdObject> Collection<T> getExternalObjects(Class<T>);
}
```

`IV8ProjectManager.getProjects()` повертає і такі проєкти — резолвінг через наявний
`V8Access` + `instanceof IExternalObjectProject`.

### com._1c.g5.v8.dt.metadata_18.0.100.v202605050943.jar

Пакет `com._1c.g5.v8.dt.metadata.mdclass`: `MdObject` (є `getName()`, `eClass()`),
топ-об'єкти `ExternalDataProcessor` (.epf) і `ExternalReport` (.erf) — у коді тип
визначаємо за `eClass().getName()`, без прямого імпорту цих класів.

### Що НЕ підходить (перевірено і відкинуто)

- `com._1c.g5.v8.dt.export_8.0.0.jar` (`IExportService`, `IExportOperationFactory`,
  `IExportArtifactBuilder`) — це експорт у **XML-формат 1С** (вигрузка вихідників),
  а не бінарник; dumper використовує його лише як проміжний крок.
- `com._1c.g5.v8.dt.export.ui_3.0.2800.jar` (`ExportExternalObjectWizard`) — UI-візард
  експорту зовнішніх об'єктів у **каталог XML** (повідомлення сторінки: «To directory»);
  теж не збирає .epf.
- Версія платформи (`Version`, `IRuntimeVersionSupport`) для `dump` не передається —
  dumper визначає її сам через `IRuntimeVersionSupport.getRuntimeVersion(IProject)`.

## Потоки і прогрес

UI-тред не потрібен: dump виконує зовнішній процес товстого клієнта і файлові
операції. У інструменті використано `org.eclipse.core.runtime.NullProgressMonitor`.
Виклик довгий (запуск 1cv8) — це нормально для tools/call.

Передперевірка перед збиранням — `IExternalObjectDumpSupport.validateDumpGeneration(IProject)`:
якщо `IStatus` з severity ERROR (немає пов'язаної ІБ/платформи), інструмент кидає
зрозумілу помилку без запуску процесу.

## Jar-и для компіляції (додати в $cp у build.ps1)

До наявного списку build.ps1 додати (точні імена в EDT 2025.2.6):

- `com._1c.g5.v8.dt.platform.services.core_21.0.0.v202605050943.jar` — dump-інтерфейси;
- `com._1c.g5.v8.dt.metadata_18.0.100.v202605050943.jar` — `MdObject`.

Увага: актуальний build.ps1 (v0.6.2) вже відстає від джерел — tools/docs
(`com._1c.g5.v8.dt.platform.doc_3.0.0.v202605050943.jar`) і ValidateQueryTool
(`org.eclipse.xtext_2.33.0`, `org.eclipse.xtext.util_2.33.0`, `org.eclipse.xtext.ui_*`)
компілюються лише з цими додатковими jar-ами. Повний список перевірено компіляцією
всіх .java (javac --release 17, exit 0).

## Import-Package для MANIFEST.MF (при інтеграції)

```
com._1c.g5.v8.dt.metadata.mdclass;resolution:=optional,
com._1c.g5.v8.dt.platform.services.core.dump;resolution:=optional
```

(`com._1c.g5.v8.dt.core.platform` вже імпортується; обидва пакети експортуються
своїми бандлами: `...metadata.mdclass` — version 8.x, `...services.core.dump` — version 4.0.0.)

Реєстрація інструмента — додати `new ExportObjectTool()` у ToolRegistry (не робилось
у межах цього дослідження).

## План тестування

1. **Негативний, поточний workspace (retail):** `export_object {"outputPath":"D:\\tmp\\x.epf"}`
   → очікується помилка «У workspace немає проєктів зовнішніх обробок/звітів».
2. **Негативний, не-external проєкт:** `{"project":"retail", ...}` → «не є проєктом
   зовнішніх обробок/звітів (ConfigurationProject)».
3. **Підготовка позитивного кейсу:** в EDT створити проєкт зовнішньої обробки
   (File > New > Project > 1C:Enterprise > Зовнішні обробки і звіти), пов'язати з ІБ
   (властивості проєкту або батьківська конфігурація з ІБ).
4. **Валідація параметрів:** без outputPath; відносний шлях; розширення .txt;
   .erf для обробки (очікується підказка про .epf); неіснуючий objectName
   (у помилці — список доступних об'єктів).
5. **Позитивний:** `{"project":"МояОбробка","objectName":"МояОбробка","outputPath":"D:\\tmp\\МояОбробка.epf"}`
   → файл створено, у відповіді sizeBytes > 0; відкрити .epf у конфігураторі/підключити в БСП.
6. **Без пов'язаної ІБ:** відв'язати ІБ від проєкту → очікується помилка передперевірки
   validateDumpGeneration (без запуску 1cv8).
7. **Звіт:** аналогічно з ExternalReport і .erf.
