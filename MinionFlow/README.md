# MinionFlow IntelliJ Plugin

Плагин для IntelliJ IDEA, интегрирующий среду разработки с платформой распределённого исполнения задач **MinionFlow**. Позволяет создавать проекты под платформу, собирать их в JAR, загружать на сервер и запускать как распределённые задачи — без выхода из IDE.


---

## Возможности

- **Wizard** для нового проекта (Maven, Java 21, шаблоны под stateless / swarm-sync режимы)
- **Сборка JAR** одной кнопкой — определяет Maven или Gradle автоматически
- **Tool Window** в IDE с полным циклом работы:
  - Авторизация на платформе (accessJWT + refreshJWT)
  - Управление проектами и артефактами (upload / update / delete JAR)
  - Конфигурация execution config через визуальную форму с двусторонней синхронизацией с raw JSON
  - Валидация и форматирование JSON
  - Запуск задачи и проверка статуса
- **Mock-бекенд** для разработки и демо без живого сервера
- **Безопасное хранение секретов** (refreshJWT в IntelliJ PasswordSafe → Windows Credentials Manager)
- **Автоматический refresh accessJWT** при 401 с прозрачным повтором запроса

---

## Архитектура

Плагин — клиент к серверной части платформы MinionFlow:

| Сервис | Назначение |
|---|---|
| `identity-service` | Авторизация, сессии, refresh access JWT |
| `project-service` | Проекты пользователя (CRUD + пагинация) |
| `artifact-service` | JAR-артефакты, задачи, статусы исполнения |

Связь — HTTP/JSON. Авторизация по `Authorization: Bearer <accessJWT>`. RefreshJWT передаётся через HTTP-only cookie и хранится в IntelliJ PasswordSafe.

---

## Требования

- **JDK 21**
- **IntelliJ IDEA 2025.3+** (Community или Ultimate)
- **Gradle wrapper** в комплекте — отдельно ставить ничего не нужно

---

## Быстрый старт

### Сборка дистрибутива

```powershell
.\gradlew.bat buildPlugin
```

Готовый ZIP появится в `build/distributions/microtask-plugin-1.0-SNAPSHOT.zip`. Установить можно через **Settings → Plugins → ⚙ → Install Plugin from Disk**.

### Запуск в sandbox-IDE

```powershell
.\gradlew.bat runIde
```

Стартует изолированная копия IDEA с подключённым плагином. Основная установка не затрагивается.

### Тесты

```powershell
.\gradlew.bat test
```

Отчёт: `build/reports/tests/test/index.html`.

---

## Конфигурация

**File → Settings → MicroTask**:

| Поле | Описание |
|---|---|
| `Use local mock backend` | Оффлайн-режим без HTTP-запросов |
| `Mock data directory` | Где mock хранит данные (по умолчанию `~/.microtask-intellij/mock`) |
| `Identity base URL` | URL identity-service (например `https://your-host/identity-service`) |
| `Project base URL` | URL project-service |
| `Artifact base URL` | URL artifact-service |
| `Task API base URL` | Базовый URL для submit/status задач |
| `Future SDK → Coordinates` | Maven-координаты SDK для генерируемых проектов (опционально) |
| `Future SDK → Repository URL` | Кастомный Maven-репозиторий для SDK (опционально) |

По умолчанию все URL пустые — заполняй под свой инстанс платформы. Если нужен оффлайн — включи галочку Use mock backend.

---

## Сценарий использования

1. Открой Tool Window: **View → Tool Windows → MicroTask** (или **Tools → MicroTask → Open Tool Window**).
2. Введи логин/email и пароль → **Login**. RefreshJWT сохранится в безопасное хранилище.
3. **Load projects** — подтянет список доступных проектов.
4. Выбери проект → автоматически подгрузятся артефакты и инпуты.
5. Открой Maven/Gradle-проект, который собирается в JAR.
6. **Build artifact** — соберёт JAR, путь сохранится в настройках.
7. **Run task (build + upload + start)** — полный пайплайн: собрать → залить → запустить.
8. **Check task status** — опрашивает статус по ID.

---

## Создание нового проекта

**File → New → Project → MicroTask** — визард с выбором execution mode (stateless / swarm-sync) и base package. Генерирует:

- `pom.xml` со всеми зависимостями (включая SDK из настроек, если задан)
- `Task.java`, `Input.java`, `Output.java` — заготовки кода
- `State.java` — только для swarm-sync
- `microtask.json` — дефолтная конфигурация запуска
- `.gitignore`

После завершения Maven автоматически импортирует проект.

---

## Структура исходников

```
src/main/kotlin/io/microtask/microtaskplugin/
├── api/
│   ├── MicroTaskApiClient.kt        — HTTP-клиент: auth, проекты, артефакты, задачи
│   └── LocalMockBackend.kt          — оффлайн-реализация (файловая)
├── settings/
│   ├── MicroTaskSettingsService.kt        — persistent state + PasswordSafe
│   └── MicroTaskSettingsConfigurable.kt   — страница настроек
├── ui/
│   ├── MicroTaskToolWindowFactory.kt   — точка входа в Tool Window
│   ├── MicroTaskToolWindowPanel.kt     — основная панель с кнопками
│   ├── ExecutionConfigForm.kt          — таб Options + Raw JSON
│   ├── RunPipelineRunner.kt            — оркестрация build+upload+start
│   ├── JsonPathHelpers.kt              — утилиты для путей в JSON
│   └── RunConfigValidator.kt           — парсинг и валидация конфига
├── wizard/
│   ├── MicroTaskProjectGenerator.kt          — генератор в File → New Project
│   └── MicroTaskStarterProjectScaffolder.kt  — генерация файлов
├── actions/
│   ├── BuildJarAction.kt           — пункт меню "Build artifact"
│   └── OpenToolWindowAction.kt     — пункт меню "Open Tool Window"
└── util/
    └── MicroTaskJarBuilder.kt      — обёртка для запуска Maven/Gradle

src/main/resources/META-INF/
├── plugin.xml       — манифест: extension points, actions, depends
└── pluginIcon.svg   — иконка плагина

src/test/kotlin/     — 51 unit-тест в 4 файлах
```

---

## Тестирование

51 unit-тест, все проходят:

| Suite | Тестов | Что покрывает |
|---|---|---|
| `JsonPathHelpersTest` | 21 | Read/set значений по пути в JSON, edge-cases (missing keys, wrong types) |
| `RunConfigValidatorTest` | 12 | Парсинг JSON, валидация структуры execution config, форматирование |
| `FormatTaskStatusTest` | 6 | Корректная отрисовка статуса задачи, поддержка `taskStatus` и `status` |
| `MicroTaskStarterProjectScaffolderTest` | 12 | Генерация pom.xml, Task.java, microtask.json для обоих режимов |

Покрыта вся чистая бизнес-логика. UI-компоненты, HTTP-вызовы и запуск внешних процессов (Maven/Gradle) тестируются вручную через `runIde`.

---

## Технологии

| Что | Версия | Зачем |
|---|---|---|
| Kotlin | 2.1.20 | Основной язык |
| IntelliJ Platform | 2025.3.2 | Целевая платформа |
| `org.jetbrains.intellij.platform` Gradle plugin | 2.11.0 | Сборка плагина |
| Gson | 2.13.1 | JSON парсинг |
| OkHttp | 4.12.0 | Multipart upload (для остального — встроенный `java.net.http.HttpClient`) |
| java-jwt | 4.5.0 | Декодирование JWT для определения expiry |
| JUnit | 4.13.2 | Unit-тесты |

---

## Известные ограничения

- **Нет live-обновлений** — статус задачи нужно опрашивать вручную через **Check task status** (WebSocket пока не интегрирован).
- UI на английском, **локализации на русский** нет.

