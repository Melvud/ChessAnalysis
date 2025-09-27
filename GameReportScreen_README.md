# GameReportScreen - Экран анализа шахматной партии

## Описание

`GameReportScreen` - это полнофункциональный экран для анализа шахматных партий, созданный с использованием Jetpack Compose и Material 3. Экран максимально похож на интерфейс chess.com и включает все необходимые компоненты для анализа партий.

## Основные компоненты

### 1. GameReportScreen
Главный экран, который объединяет все компоненты:
- TopAppBar с навигацией и действиями
- CoachBubble с анализом хода
- Центральная область с EvalBar и BoardCanvas
- MovesCarousel для навигации по ходам
- Кнопки действий

### 2. GameReportViewModel
ViewModel с однонаправленным потоком данных:
- `GameReportUiState` - состояние экрана
- `GameReportIntent` - интенты пользователя
- Обработка навигации по ходам
- Управление состоянием доски

### 3. EvalBar
Вертикальный индикатор оценки позиции:
- Анимированное отображение оценки
- Нормализация значений (-8 до +8 пешек)
- Поддержка мата (прижимается к краям)
- Плавные анимации при смене позиции

### 4. BoardCanvas
Шахматная доска с Canvas:
- Отображение фигур из SVG ассетов
- Подсветка последнего хода
- Стрелки для показа направления хода
- Поддержка FEN нотации

### 5. MovesCarousel
Карусель ходов с center-snapping:
- Использует Accompanist Snapper
- Центрированный снаппинг
- Чипы качества ходов
- Навигация стрелками

### 6. IconsMapping
Маппинг MoveClass на иконки и цвета:
- Поддержка всех типов ходов
- Material Design иконки
- Цветовая схема как в chess.com

## Использование

```kotlin
@Composable
fun MyScreen() {
    val report = // ваш FullReport
    
    GameReportScreen(
        report = report,
        onBack = { /* навигация назад */ },
        onNextKeyMoment = { /* следующий ключевой момент */ }
    )
}
```

## Зависимости

Добавлены в `build.gradle.kts`:
```kotlin
implementation("dev.chrisbanes.snapper:snapper:0.3.0")
```

## Особенности

1. **Адаптивность**: Поддерживает разные размеры экранов
2. **Анимации**: Плавные переходы и анимации
3. **Доступность**: Поддержка screen readers
4. **Производительность**: Оптимизированная отрисовка
5. **Темная тема**: Поддержка Material 3 тем

## Структура файлов

```
ui/
├── screens/
│   ├── GameReportScreen.kt
│   ├── GameReportViewModel.kt
│   └── GameReportScreenPreview.kt
└── components/
    ├── EvalBar.kt
    ├── BoardCanvas.kt
    ├── MovesCarousel.kt
    └── IconsMapping.kt
```

## Превью

Созданы превью для светлой и тёмной тем:
- `GameReportScreenPreview()` - светлая тема
- `GameReportScreenDarkPreview()` - тёмная тема

## Тестирование

Экран готов к использованию и тестированию. Все компоненты следуют принципам Material Design и обеспечивают отличный пользовательский опыт.
