package litra.mmm; // **Важно: Замените на ваш пакет**

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_FILE_REQUEST_CODE = 1001;
    private static final int TEXT_TO_HIDE_INTERVAL_MS = 5 * 60 * 1000; // 5 минут
    private static final int TEST_INTERVAL_MS = 10 * 1000; // 10 секунд для теста
    private static final float INITIAL_HIDE_PERCENTAGE = 0.10f; // 10%
    private static final float HIDE_PERCENTAGE_INCREASE = 0.15f; // +15%

    private EditText poemEditText;
    private TextView timerTextView;
    private TextView scoreTextView;
    private Button loadButton, saveButton, checkButton;

    private String originalPoem = ""; // Хранит оригинальный текст стиха
    private String currentPoemState = ""; // Хранит текущее состояние текста в EditText

    private long startTime = 0L;
    private long timeInMilliseconds = 0L;
    private long timeSwapBuff = 0L;
    private long updatedTime = 0L;
    private int seconds, minutes, milliseconds;
    private Handler handler = new Handler();

    private float currentHidePercentage = INITIAL_HIDE_PERCENTAGE;
    private int wordsToHideCount = 0;
    private List<String> originalWords = new ArrayList<>(); // Для более точного скрытия/проверки

    // Для сохранения состояния
    private static final String KEY_ORIGINAL_POEM = "originalPoem";
    private static final String KEY_CURRENT_POEM_STATE = "currentPoemState";
    private static final String KEY_START_TIME = "startTime";
    private static final String KEY_TIME_IN_MILLISECONDS = "timeInMilliseconds";
    private static final String KEY_TIMER_RUNNING = "timerRunning";
    private static final String KEY_CURRENT_HIDE_PERCENTAGE = "currentHidePercentage";
    private static final String KEY_ORIGINAL_WORDS = "originalWords"; // Для сохранения слов
    private static final String KEY_HAS_RUN_FOR_TEST = "hasRunForTest"; // Флаг для интервала


    private boolean isTimerRunning = false;
    private boolean hasRunForTestInterval = false; // Флаг, чтобы избежать мгновенного скрытия при старте


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        poemEditText = findViewById(R.id.poemEditText);
        timerTextView = findViewById(R.id.timerTextView);
        scoreTextView = findViewById(R.id.scoreTextView);
        loadButton = findViewById(R.id.loadButton);
        saveButton = findViewById(R.id.saveButton);
        checkButton = findViewById(R.id.checkButton);

        // Инициализация таймера
        timerTextView.setText("Таймер: 00:00");

        // Восстановление состояния, если оно есть
        if (savedInstanceState != null) {
            originalPoem = savedInstanceState.getString(KEY_ORIGINAL_POEM, "");
            poemEditText.setText(savedInstanceState.getString(KEY_CURRENT_POEM_STATE, ""));
            startTime = savedInstanceState.getLong(KEY_START_TIME, 0L);
            timeInMilliseconds = savedInstanceState.getLong(KEY_TIME_IN_MILLISECONDS, 0L);
            isTimerRunning = savedInstanceState.getBoolean(KEY_TIMER_RUNNING, false);
            currentHidePercentage = savedInstanceState.getFloat(KEY_CURRENT_HIDE_PERCENTAGE, INITIAL_HIDE_PERCENTAGE);
            currentPoemState = poemEditText.getText().toString(); // Обновляем из EditText

            // Восстановление списка слов, если он был сохранен
            if (savedInstanceState.containsKey(KEY_ORIGINAL_WORDS)) {
                originalWords = savedInstanceState.getStringArrayList(KEY_ORIGINAL_WORDS);
            }

            if (isTimerRunning) {
                // Обновляем время и запускаем таймер снова
                timeSwapBuff = timeInMilliseconds; // Используем сохраненное время
                startTime = SystemClock.uptimeMillis() - timeSwapBuff;
                handler.postDelayed(updateTimerThread, 0); // Запускаем обновление сразу
            } else {
                // Если таймер не был запущен, просто отображаем сохраненное время
                updateTimerTextView();
            }
            hasRunForTestInterval = savedInstanceState.getBoolean(KEY_HAS_RUN_FOR_TEST, false);
        }

        // Обработчики нажатий кнопок
        loadButton.setOnClickListener(v -> openFilePicker());
        saveButton.setOnClickListener(v -> savePoemToFile()); // TODO: Реализовать сохранение
        checkButton.setOnClickListener(v -> checkPoem());

        // Обработчик изменений текста в EditText
        poemEditText.setOnClickListener(v -> {
            if (!isTimerRunning) {
                startTimer();
            }
        });
    }

    // Метод для открытия файлового менеджера
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain"); // Указываем, что ищем текстовые файлы
        startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
    }

    // Обработка результата выбора файла
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    originalPoem = readTextFromUri(uri);
                    poemEditText.setText(originalPoem);
                    currentPoemState = originalPoem; // Сохраняем как текущее состояние
                    originalWords = getWordsFromPoem(originalPoem); // Парсим слова для скрытия
                    resetTimerAndPoemState(); // Сбрасываем таймер и процент скрытия при загрузке нового стиха
                    Toast.makeText(this, "Стих успешно загружен!", Toast.LENGTH_SHORT).show();
                } catch (IOException e) {
                    Toast.makeText(this, "Ошибка при чтении файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // Чтение текста из Uri
    private String readTextFromUri(Uri uri) throws IOException {
        StringBuilder stringBuilder = new StringBuilder();
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        }
        return stringBuilder.toString();
    }

    // Метод для сохранения стиха (простая реализация: сохранение в переменную)
    // Для рельного сохранения в файл потребуется DocumentFile API или Storage Access Framework
    private void savePoemToFile() {
        if (originalPoem.isEmpty()) {
            Toast.makeText(this, "Нет стиха для сохранения.", Toast.LENGTH_SHORT).show();
            return;
        }
        // В данном примере, "сохранение" означает, что мы сохраняем текущий оригинальный стих
        // в переменной originalPoem, который может быть загружен или изменен.
        // Реальное сохранение на устройство потребует более сложной логики с использованием
        // DocumentFile API или Storage Access Framework для выбора места сохранения.
        // Для демонстрации, просто подтвердим, что текст сохранен в памяти.

        // Чтобы реализовать реальное сохранение файла:
        // 1. Требуется использовать ACTION_CREATE_DOCUMENT Intent
        // 2. Использовать DocumentFile API для работы с созданными файлами.
        // Это выходит за рамки простого примера, поэтому пока ограничимся сохранением в память.

        Toast.makeText(this, "Стих сохранен (в памяти приложения).", Toast.LENGTH_SHORT).show();
    }

    // Запуск или возобновление таймера
    private void startTimer() {
        if (startTime == 0L) { // Если это первый запуск
            timeInMilliseconds = 0L;
        }
        startTime = SystemClock.uptimeMillis() - timeInMilliseconds;
        handler.postDelayed(updateTimerThread, 0); // Запускаем обновление сразу
        isTimerRunning = true;
        // Запускаем скрытие слов через определенный интервал
        handler.postDelayed(hideWordsRunnable, getIntervalToHideWords());
    }

    // Остановка таймера
    private void stopTimer() {
        handler.removeCallbacks(updateTimerThread);
        handler.removeCallbacks(hideWordsRunnable); // Останавливаем и скрытие слов
        isTimerRunning = false;
        hasRunForTestInterval = false; // Сбрасываем флаг при остановке
    }

    // Обновление текста таймера
    private void updateTimerTextView() {
        minutes = (int) (timeInMilliseconds / 1000) / 60;
        seconds = (int) (timeInMilliseconds / 1000) % 60;
        milliseconds = (int) (timeInMilliseconds % 1000);
        timerTextView.setText(String.format(Locale.getDefault(), "Таймер: %02d:%02d", minutes, seconds));
    }

    // Runnable для обновления таймера
    private Runnable updateTimerThread = new Runnable() {
        public void run() {
            timeInMilliseconds = SystemClock.uptimeMillis() - startTime;
            updatedTime = timeInMilliseconds;
            updateTimerTextView();
            if (isTimerRunning) {
                handler.postDelayed(this, 0); // Повторяем каждые 0 мс для максимальной точности
            }
        }
    };

    // Runnable для скрытия слов
    private Runnable hideWordsRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTimerRunning && !originalPoem.isEmpty()) {
                hideRandomWords();
                // Рассчитываем следующий интервал
                handler.postDelayed(this, getIntervalToHideWords());
            }
        }
    };

    // Получение интервала для скрытия слов (тестовый или реальный)
    private long getIntervalToHideWords() {
        // Если у нас есть оригинальные слова и они не скрыты полностью,
        // и мы уже запускали скрытие для теста, используем реальный интервал.
        // Иначе (если это первый запуск или есть условие для теста), используем тестовый.
        if (originalWords.size() > 0 && currentHidePercentage < 1.0f && hasRunForTestInterval) {
            return TEXT_TO_HIDE_INTERVAL_MS;
        } else {
            hasRunForTestInterval = true; // Устанавливаем флаг, что мы запустили тестовый интервал
            return TEST_INTERVAL_MS;
        }
    }

    // Скрывает случайные слова в тексте
    private void hideRandomWords() {
        if (originalWords.isEmpty()) return;

        // Рассчитываем, сколько слов нужно скрыть на данном шаге
        int totalWords = originalWords.size();
        int wordsToCurrentlyHide = (int) (totalWords * currentHidePercentage);

        // Увеличиваем количество скрываемых слов, если есть новые слова для скрытия
        if (wordsToCurrentlyHide > wordsToHideCount) {
            wordsToHideCount = wordsToCurrentlyHide;
        }

        // Гарантируем, что мы не скрываем больше слов, чем есть
        wordsToHideCount = Math.min(wordsToHideCount, totalWords);

        // Получаем текущий текст из EditText
        String currentText = poemEditText.getText().toString();
        String[] currentWords = currentText.split("\\s+"); // Разделяем по пробелам

        List<Integer> wordIndicesToHide = new ArrayList<>();
        Random random = new Random();

        // Находим индексы слов, которые еще не скрыты
        List<Integer> availableIndices = new ArrayList<>();
        for (int i = 0; i < currentWords.length; i++) {
            // Проверяем, не является ли слово уже скрыто (т.е. состоит из ____)
            if (!currentWords[i].equals("____")) {
                availableIndices.add(i);
            }
        }
        Collections.shuffle(availableIndices, random); // Перемешиваем, чтобы выбрать случайные

        // Выбираем индексы для скрытия
        int count = 0;
        for (int index : availableIndices) {
            if (count < (wordsToHideCount - countWordsAlreadyHidden(currentText)) && index < currentWords.length) {
                wordIndicesToHide.add(index);
                count++;
            } else if (count >= (wordsToHideCount - countWordsAlreadyHidden(currentText))) {
                break;
            }
        }

        // Формируем новый текст с замененными словами
        StringBuilder sb = new StringBuilder();
        int wordIndex = 0;
        for (String word : currentWords) {
            if (wordIndicesToHide.contains(wordIndex)) {
                sb.append("____"); // Заменяем слово
            } else {
                sb.append(word);
            }
            sb.append(" "); // Добавляем пробел после каждого слова
            wordIndex++;
        }

        // Устанавливаем новый текст в EditText.
        // Важно: сохраняем позицию курсора, если она есть.
        int selectionStart = poemEditText.getSelectionStart();
        poemEditText.setText(sb.toString().trim()); // trim() для удаления последнего пробела
        // Восстанавливаем позицию курсора
        if (selectionStart != -1) {
            poemEditText.setSelection(Math.min(selectionStart, poemEditText.getText().length()));
        }

        // Увеличиваем процент скрытия для следующего шага
        currentHidePercentage += HIDE_PERCENTAGE_INCREASE;
        // Гарантируем, что процент не превысит 1.0 (100%)
        currentHidePercentage = Math.min(currentHidePercentage, 1.0f);
    }

    // Вспомогательный метод для подсчета уже скрытых слов (____)
    private int countWordsAlreadyHidden(String text) {
        int count = 0;
        String[] words = text.split("\\s+");
        for (String word : words) {
            if (word.equals("____")) {
                count++;
            }
        }
        return count;
    }


    // Парсит текст стиха на слова
    private List<String> getWordsFromPoem(String poem) {
        List<String> words = new ArrayList<>();
        // Разделяем по пробелам, знакам препинания и переводам строк
        String[] splitWords = poem.replaceAll("[^a-zA-Zа-яА-ЯёЁ\\s]", "").toLowerCase().split("\\s+");
        for (String word : splitWords) {
            if (!word.trim().isEmpty()) {
                words.add(word.trim());
            }
        }
        return words;
    }

    // Метод для сброса таймера и состояния стихотворения
    private void resetTimerAndPoemState() {
        stopTimer();
        timeInMilliseconds = 0L;
        startTime = 0L;
        updateTimerTextView();
        currentHidePercentage = INITIAL_HIDE_PERCENTAGE; // Сбросить процент скрытия
        wordsToHideCount = 0; // Сбросить счетчик скрытых слов
        hasRunForTestInterval = false; // Сбросить флаг тестового интервала
    }

    // Логика проверки стихотворения
    private void checkPoem() {
        stopTimer(); // Останавливаем таймер при проверке

        if (originalPoem.isEmpty()) {
            Toast.makeText(this, "Сначала загрузите или введите стих.", Toast.LENGTH_SHORT).show();
            return;
        }

        String typedPoem = poemEditText.getText().toString();

        // Очищаем проверяемый текст от лишних пробелов и приводим к одному регистру
        String cleanedTypedPoem = typedPoem.replaceAll("\\s+", " ").trim();
        String cleanedOriginalPoem = originalPoem.replaceAll("\\s+", " ").trim();

        double score = calculateScore(cleanedOriginalPoem, cleanedTypedPoem);

        showResultDialog(score);
    }

    // Расчет балла (0-100)
    private double calculateScore(String original, String typed) {
        List<String> originalWordsList = getWordsFromPoem(original);
        List<String> typedWordsList = getWordsFromPoem(typed);

        int correctWords = 0;
        int totalWords = originalWordsList.size();

        if (totalWords == 0) return 0.0;

        // Простой подсчет совпавших слов
        for (String originalWord : originalWordsList) {
            boolean found = false;
            for (String typedWord : typedWordsList) {
                if (originalWord.equalsIgnoreCase(typedWord)) {
                    correctWords++;
                    typedWordsList.remove(typedWord); // Удаляем, чтобы избежать повторного подсчета
                    found = true;
                    break;
                }
            }
        }

        // Процент правильных слов
        return (double) correctWords / totalWords * 100.0;
    }

    // Показ AlertDialog с результатом
    private void showResultDialog(double score) {
        String formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);

        String message = String.format(Locale.getDefault(),
                "Время: %s\nСчет: %.2f%%",
                formattedTime, score);

        new AlertDialog.Builder(this)
                .setTitle("Результат проверки")
                .setMessage(message)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        // Можно добавить логику для повторного старта, если нужно
                        // resetTimerAndPoemState();
                        // poemEditText.setText(""); // Очистить поле для нового стиха
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show();
    }

    // Сохранение состояния при повороте экрана или сворачивании
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_ORIGINAL_POEM, originalPoem);
        outState.putString(KEY_CURRENT_POEM_STATE, poemEditText.getText().toString());
        outState.putLong(KEY_START_TIME, startTime);
        outState.putLong(KEY_TIME_IN_MILLISECONDS, timeInMilliseconds);
        outState.putBoolean(KEY_TIMER_RUNNING, isTimerRunning);
        outState.putFloat(KEY_CURRENT_HIDE_PERCENTAGE, currentHidePercentage);
        outState.putStringArrayList(KEY_ORIGINAL_WORDS, new ArrayList<>(originalWords)); // Сохраняем список слов
        outState.putBoolean(KEY_HAS_RUN_FOR_TEST, hasRunForTestInterval);
    }

    // ** Важно: onRestoreInstanceState уже вызывается автоматически,
    // если вы сохранили данные в onSaveInstanceState.
    // Нет необходимости вручную переименовывать его в onRestoreInstanceState.
    // Если вы хотите явно управлять процессом, можете использовать:
    // @Override
    // public void onRestoreInstanceState(Bundle savedInstanceState) {
    //     super.onRestoreInstanceState(savedInstanceState);
    //     // Восстановление элементов жизненного цикла, если необходимо
    //     // Текущий код восстановления уже находится в onCreate, что является стандартной практикой.
    // }

    // Обработка жизненного цикла Activity
    @Override
    protected void onPause() {
        super.onPause();
        if (isTimerRunning) {
            // При сворачивании приложения, таймер и скрытие слов должны продолжать работать
            // или как минимум сохраняться. Если мы останавливаем здесь, то onResume должен его возобновить.
            // Для простоты, давайте остановим и сохраним состояние.
            timeSwapBuff = timeInMilliseconds; // Сохраняем текущее время
            handler.removeCallbacks(updateTimerThread);
            handler.removeCallbacks(hideWordsRunnable);
            // isTimerRunning = false; // Не устанавливаем в false, чтобы знать, что нужно возобновить
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isTimerRunning) {
            // Возобновляем таймер
            startTime = SystemClock.uptimeMillis() - timeSwapBuff;
            handler.postDelayed(updateTimerThread, 0);
            // Возобновляем скрытие слов
            handler.postDelayed(hideWordsRunnable, getIntervalToHideWords());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Очищаем callback'и, чтобы избежать утечек памяти
        handler.removeCallbacks(updateTimerThread);
        handler.removeCallbacks(hideWordsRunnable);
    }
}
