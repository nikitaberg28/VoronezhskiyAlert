# Заметки по переносу на Minecraft 26.x (после третьего прохода)

Продолжение работы после того, как вы прислали декомпилированные `Screen.java`,
`EditBox.java`, `Button.java`, `ChatFormatting.java`, `ClientLevel.java`,
`Window.java`. Прогресс: 100 → 71 → (следующая сборка должна показать
заметно меньше ошибок; конкретное число появится в вашем следующем логе).

## Крупные структурные изменения, подтверждённые декомпиляцией в этом раунде

- **`ModernButton`** теперь наследует `AbstractButton` напрямую (не `Button`,
  у которого конструктор `protected` и завязан на свои внутренние
  `OnPress`/`CreateNarration` интерфейсы). Свой `PressAction` объявлен
  локально в `ModernButton` — так проще и корректнее для этого случая.
- **`Screen`**: `addDrawableChild(...)` не существует → `addRenderableWidget(...)`.
  `render(...)` не существует как override-точка → `extractRenderState(...)`.
  `shouldPause()` → `isPauseScreen()`. `close()` не существует → `onClose()`.
  Поля `font`/`minecraft`/`width`/`height` — как и предполагалось, они
  действительно называются именно так.
- **`EditBox`**: `setText/getText` → `setValue/getValue`. `setPlaceholder` →
  `setHint`. `setTextPredicate` не существует — фильтрация цифр теперь
  реализована через `setResponder(...)`, откатывающий недопустимый ввод.
- **`GuiGraphicsExtractor`**: `drawTextWithShadow(...)` и
  `drawCenteredTextWithShadow(...)` не существуют. Подтверждённая замена —
  `graphics.text(Font, Component, x, y, color, shadow)` (взято из
  `EditBox.extractWidgetRenderState`). Центрирование текста теперь считается
  вручную через `font.width(...)`, как это делает сам `EditBox`.
- **`ChatFormatting`**: оказалось, что это **чистый enum §-кодов форматирования
  без какой-либо цветовой информации вообще** — ни `isColor()`, ни
  `getColor()`, ни `getColorValue()` не существует. Функция подбора ближайшего
  цвета team/tablist теперь использует захардкоженную таблицу из 16 стандартных
  vanilla-цветов (их RGB-значения — стабильная публичная часть формата текста
  Minecraft, не меняется между версиями) вместо чтения из самого enum.
- **`ClientLevel`**: `getRegistryKey()` не существует → `dimension()`
  (возвращает `ResourceKey<Level>`). `getPlayers()` не существует →
  `players()` (возвращает `List<AbstractClientPlayer>`).
- **`Window`**: `getScaledWidth()/getScaledHeight()` не существуют →
  `getGuiScaledWidth()/getGuiScaledHeight()`.
- **`Font`**: нет поля `fontHeight` → используйте `lineHeight` (константа
  `9`, подтверждено в `Font.java`).

## Всё ещё TODO-VERIFY (нужны ещё файлы, если хотите закрыть их точно)

- **`HudElementRegistry`** / **`VanillaHudElements`**: сам класс и метод
  `attachElementBefore(...)` подтверждены официальными примерами кода Fabric
  для 26.1.2, но точный **пакет импорта** не подтверждён — страницы
  документации не показывают импорты в примерах. Если следующая сборка
  всё ещё не найдёт этот класс, откройте `fabric-rendering-v1-...jar` как
  архив или найдите класс через `Ctrl+Shift+N` в IDEA.
- `TextColor.getValue()` — предположение, не проверено напрямую (`TextColor.java`
  не декомпилировался).
- `context.getMatrices()` и `context.drawItem(...)` на `GuiGraphicsExtractor` —
  не встречались ни в одном декомпилированном файле, нужно свериться в IDE.
- `SoundEvents.BLOCK_BELL_USE` — само поле не подтверждено (файл `SoundEvents.java`
  не декомпилировался), также неясно, `SoundEvent` это или `Holder<SoundEvent>`
  теперь (в `ClientLevel.java` встречались оба варианта в разных методах).
- `Camera` методы (`getCameraPos`, `getPitch`, `getYaw`, `isReady`) — класс
  использовался, но сам не декомпилировался.
- `Options.getFov()` и `GameRenderer.getBasicProjectionMatrix(int)` — не
  подтверждены, `Options.java`/`GameRenderer.java` не декомпилировались.
- `Entity.distanceToSqr(Entity)` — перегрузка для двух Entity не подтверждена
  напрямую (видел только `distanceToSqr(Vec3)`).
- `Player.connection.getPlayerInfo(UUID)` — используется в трёх местах для
  получения tab-list записи игрока; не подтверждено напрямую.

Если пришлёте декомпилированные `SoundEvents.java` (или хотя бы `Ctrl+F` по
"BELL"), `Camera.java`, `Options.java` (или просто структуру через Ctrl+F12),
`GameRenderer.java`, `Entity.java` и `TextColor.java` — смогу закрыть
практически всё оставшееся.
