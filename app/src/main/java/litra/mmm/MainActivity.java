package litra.mmm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final long INTERVAL = 5 * 60 * 1000L;
    private static final int REQUEST_SAVE_PROGRESS = 200;
    private LinearLayout urlPanel;
    private EditText urlEditText;
    private EditText poemEditText;
    private TextView timerScoreTextView;
    private Button loadInternetButton;
    private Button nextStageButton;
    private Button saveButton;
    private Button checkButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String originalText = "";
    private ArrayList<Token> tokens = new ArrayList<>();
    private ArrayList<String> originalWords = new ArrayList<>();
    private ArrayList<Integer> hideOrder = new ArrayList<>();
    private HashSet<Integer> hiddenWordIndexes = new HashSet<>();
    private HashMap<Integer, String> savedAnswers = new HashMap<>();
    private int hideStep = 0;
    private boolean sessionActive = false;
    private long elapsedBeforeResumeMs = 0L;
    private long lastResumeRealtimeMs = 0L;
    private long nextHideAtElapsedMs = INTERVAL;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) {
                return;
            }

            long elapsed = getCurrentElapsedMs();

            if (elapsed >= nextHideAtElapsedMs) {
                handleAutomaticStageChange();
                nextHideAtElapsedMs = getCurrentElapsedMs() + INTERVAL;
            }

            updateTimerAndScoreText();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlPanel = findViewById(R.id.urlPanel);

        urlEditText = findViewById(R.id.urlEditText);
        poemEditText = findViewById(R.id.poemEditText);
        timerScoreTextView = findViewById(R.id.timerScoreTextView);

        loadInternetButton = findViewById(R.id.loadInternetButton);
        nextStageButton = findViewById(R.id.nextStageButton);
        saveButton = findViewById(R.id.saveButton);
        checkButton = findViewById(R.id.checkButton);

        nextStageButton.setEnabled(false);
        saveButton.setEnabled(false);
        checkButton.setEnabled(false);

        loadInternetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String url = urlEditText.getText().toString().trim();

                if (url.isEmpty()) {
                    Toast.makeText(
                            MainActivity.this,
                            "Вставьте ссылку с ilibrary.ru или culture.ru.",
                            Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                if (!isSupportedPoemUrl(url)) {
                    Toast.makeText(
                            MainActivity.this,
                            "Нужна ссылка с ilibrary.ru или конкретная страница стихотворения с culture.ru.",
                            Toast.LENGTH_LONG
                    ).show();
                    return;
                }

                downloadPoemFromInternet(url);
            }
        });

        nextStageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                goToNextStageManually();
            }
        });

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSaveFilePicker();
            }
        });

        checkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showCheckResult();
            }
        });

        updateTimerAndScoreText();

        if (savedInstanceState == null) {
            poemEditText.setText("Вставьте ссылку на стих с ilibrary.ru или culture.ru и нажмите «Загрузить стих».");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (sessionActive) {
            lastResumeRealtimeMs = SystemClock.elapsedRealtime();
            startTimer();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (sessionActive) {
            elapsedBeforeResumeMs = getCurrentElapsedMs();
        }

        lastResumeRealtimeMs = 0L;
        stopTimer();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        cacheAnswersFromCurrentText();

        outState.putString("url", urlEditText.getText().toString());
        outState.putString("originalText", originalText);
        outState.putString("currentText", poemEditText.getText().toString());

        outState.putStringArrayList("originalWords", originalWords);
        outState.putIntegerArrayList("hideOrder", hideOrder);
        outState.putIntegerArrayList("hiddenWordIndexes", new ArrayList<>(hiddenWordIndexes));

        outState.putInt("hideStep", hideStep);
        outState.putBoolean("sessionActive", sessionActive);
        outState.putLong("elapsedBeforeResumeMs", getCurrentElapsedMs());
        outState.putLong("nextHideAtElapsedMs", nextHideAtElapsedMs);

        outState.putInt("urlPanelVisibility", urlPanel.getVisibility());

        Bundle answersBundle = new Bundle();

        for (Map.Entry<Integer, String> entry : savedAnswers.entrySet()) {
            answersBundle.putString(String.valueOf(entry.getKey()), entry.getValue());
        }

        outState.putBundle("savedAnswers", answersBundle);

        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        urlEditText.setText(savedInstanceState.getString("url", ""));

        originalText = savedInstanceState.getString("originalText", "");
        String currentText = savedInstanceState.getString("currentText", "");

        originalWords = savedInstanceState.getStringArrayList("originalWords");
        if (originalWords == null) {
            originalWords = new ArrayList<>();
        }

        hideOrder = savedInstanceState.getIntegerArrayList("hideOrder");
        if (hideOrder == null) {
            hideOrder = new ArrayList<>();
        }

        ArrayList<Integer> restoredHidden = savedInstanceState.getIntegerArrayList("hiddenWordIndexes");
        hiddenWordIndexes.clear();

        if (restoredHidden != null) {
            hiddenWordIndexes.addAll(restoredHidden);
        }

        savedAnswers.clear();

        Bundle answersBundle = savedInstanceState.getBundle("savedAnswers");

        if (answersBundle != null) {
            for (String key : answersBundle.keySet()) {
                try {
                    int index = Integer.parseInt(key);
                    savedAnswers.put(index, answersBundle.getString(key, ""));
                } catch (NumberFormatException ignored) {
                    // Пропускаем повреждённый ключ.
                }
            }
        }

        hideStep = savedInstanceState.getInt("hideStep", 0);
        sessionActive = savedInstanceState.getBoolean("sessionActive", false);
        elapsedBeforeResumeMs = savedInstanceState.getLong("elapsedBeforeResumeMs", 0L);
        nextHideAtElapsedMs = savedInstanceState.getLong("nextHideAtElapsedMs", INTERVAL);

        int urlPanelVisibility = savedInstanceState.getInt("urlPanelVisibility", View.VISIBLE);
        urlPanel.setVisibility(urlPanelVisibility);

        tokens = tokenize(originalText);
        poemEditText.setText(currentText);

        boolean hasPoem = !originalText.trim().isEmpty();

        nextStageButton.setEnabled(hasPoem && !isFinalStageReached());
        saveButton.setEnabled(hasPoem);
        checkButton.setEnabled(hasPoem);

        if (sessionActive) {
            lastResumeRealtimeMs = SystemClock.elapsedRealtime();
            startTimer();
        }

        updateTimerAndScoreText();
    }

    private boolean isSupportedPoemUrl(String url) {
        return isILibraryUrl(url) || isCulturePoemUrl(url);
    }

    private boolean isILibraryUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);

        return lower.startsWith("https://ilibrary.ru/")
                || lower.startsWith("http://ilibrary.ru/")
                || lower.startsWith("https://www.ilibrary.ru/")
                || lower.startsWith("http://www.ilibrary.ru/");
    }

    private boolean isCulturePoemUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);

        return lower.startsWith("https://culture.ru/poems/")
                || lower.startsWith("http://culture.ru/poems/")
                || lower.startsWith("https://www.culture.ru/poems/")
                || lower.startsWith("http://www.culture.ru/poems/");
    }

    private void downloadPoemFromInternet(final String urlText) {
        Toast.makeText(this, "Загружаю стих...", Toast.LENGTH_SHORT).show();

        loadInternetButton.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL url = new URL(urlText);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 PoemMemorizerApp");

                    int responseCode = connection.getResponseCode();

                    if (responseCode < 200 || responseCode >= 300) {
                        throw new Exception("Сервер вернул код: " + responseCode);
                    }

                    Charset charset = detectCharset(connection.getContentType(), urlText);

                    InputStream inputStream = connection.getInputStream();
                    String html = readAllText(inputStream, charset);

                    connection.disconnect();

                    String poemText = extractPoemFromSupportedSite(urlText, html);
                    final String finalText = poemText.trim();

                    if (finalText.isEmpty()) {
                        throw new Exception("Не удалось найти текст стихотворения на странице.");
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadInternetButton.setEnabled(true);
                            startNewSession(finalText);
                        }
                    });

                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadInternetButton.setEnabled(true);

                            Toast.makeText(
                                    MainActivity.this,
                                    "Ошибка загрузки: " + e.getMessage(),
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static Charset detectCharset(String contentType, String urlText) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);

            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).trim());
                } catch (Exception ignored) {
                }
            }
        }

        if (urlText.toLowerCase(Locale.ROOT).contains("ilibrary.ru")) {
            try {
                return Charset.forName("windows-1251");
            } catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }

        return StandardCharsets.UTF_8;
    }

    private static String readAllText(InputStream inputStream, Charset charset) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, charset));

        StringBuilder builder = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }

        reader.close();
        return builder.toString();
    }

    private static String extractPoemFromSupportedSite(String urlText, String html) {
        String lower = urlText.toLowerCase(Locale.ROOT);

        if (lower.contains("culture.ru/poems/")) {
            return extractPoemFromCultureHtml(html);
        }

        return extractPoemFromILibraryHtml(html);
    }

    private static String extractPoemFromILibraryHtml(String html) {
        if (html == null) {
            return "";
        }

        StringBuilder poemBuilder = new StringBuilder();

        Pattern spanPattern = Pattern.compile(
                "(?is)<span\\s+[^>]*class\\s*=\\s*[\"']?p[\"']?[^>]*>(.*?)</span>"
        );

        Matcher matcher = spanPattern.matcher(html);

        while (matcher.find()) {
            String part = matcher.group(1);
            part = htmlToPlainText(part).trim();

            if (!part.isEmpty()) {
                poemBuilder.append(part).append('\n');
            }
        }

        String poem = poemBuilder.toString().trim();

        if (!poem.isEmpty()) {
            return cutAfterStopMarker(poem);
        }

        return cutAfterStopMarker(htmlToPlainText(html).trim());
    }

    private static String extractPoemFromCultureHtml(String html) {
        if (html == null) {
            return "";
        }

        String plain = htmlToPlainText(html);
        plain = plain.replace('\u00A0', ' ');

        plain = cutAfterAnyMarker(
                plain,
                new String[]{
                        "Следующий стих",
                        "Предыдущий стих",
                        "Другие стихи этого автора",
                        "Стихи других авторов",
                        "Подборка стихотворений",
                        "Статьи и новости",
                        "Биографии",
                        "Комментарии"
                }
        );

        plain = cutAfterStopMarker(plain);

        ArrayList<String> lines = new ArrayList<>();
        String[] rawLines = plain.split("\\n");

        for (String rawLine : rawLines) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (isCultureJunkLine(line)) {
                continue;
            }

            lines.add(line);
        }

        if (lines.isEmpty()) {
            return "";
        }

        int start = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (line.equalsIgnoreCase("Каталог стихотворений")) {
                start = i + 1;
            }

            if (line.endsWith("— стихи") || line.endsWith("- стихи")) {
                start = i + 1;
            }
        }

        while (start < lines.size() && isCultureJunkLine(lines.get(start))) {
            start++;
        }
        if (lines.size() - start >= 3) {
            start += 2;
        }

        StringBuilder poemBuilder = new StringBuilder();

        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.isEmpty()) {
                continue;
            }

            poemBuilder.append(line).append('\n');
        }

        return poemBuilder.toString().trim();
    }

    private static boolean isCultureJunkLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        return lower.equals("литература")
                || lower.equals("каталог стихотворений")
                || lower.equals("image")
                || lower.startsWith("image:")
                || lower.startsWith("top.mail.ru")
                || lower.equals("афиша")
                || lower.equals("live")
                || lower.equals("спецпроекты")
                || lower.equals("кино")
                || lower.equals("музеи")
                || lower.equals("музыка")
                || lower.equals("театр")
                || lower.equals("традиции")
                || lower.equals("архитектура")
                || lower.equals("образование")
                || lower.equals("о проекте")
                || lower.equals("открытые данные")
                || lower.startsWith("©")
                || lower.contains("культура.рф")
                || lower.contains("при цитировании")
                || lower.contains("нашли опечатку")
                || lower.contains("войдите")
                || lower.contains("зарегистрируйтесь");
    }

    private static String cutAfterAnyMarker(String text, String[] markers) {
        if (text == null) {
            return "";
        }

        int bestIndex = -1;

        for (String marker : markers) {
            int index = text.indexOf(marker);

            if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
                bestIndex = index;
            }
        }

        if (bestIndex >= 0) {
            return text.substring(0, bestIndex).trim();
        }

        return text.trim();
    }

    private static String cutAfterStopMarker(String text) {
        if (text == null) {
            return "";
        }

        int markerIndex = text.indexOf('✦');

        if (markerIndex >= 0) {
            return text.substring(0, markerIndex).trim();
        }

        return text.trim();
    }

    private static String htmlToPlainText(String html) {
        String result = html;

        result = result.replaceAll("(?is)<script.*?>.*?</script>", " ");
        result = result.replaceAll("(?is)<style.*?>.*?</style>", " ");

        result = result.replaceAll("(?i)<br\\s*/?>", "\n");
        result = result.replaceAll("(?i)</p>", "\n");
        result = result.replaceAll("(?i)</div>", "\n");
        result = result.replaceAll("(?i)</span>", "\n");
        result = result.replaceAll("(?i)</li>", "\n");

        result = result.replaceAll("(?is)<[^>]+>", " ");

        result = decodeHtmlEntities(result);

        result = result.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        result = result.replaceAll(" *\\n *", "\n");
        result = result.replaceAll("\\n{3,}", "\n\n");

        return result.trim();
    }

    private static String decodeHtmlEntities(String text) {
        String result = text;

        result = result.replace("&nbsp;", " ");
        result = result.replace("&quot;", "\"");
        result = result.replace("&apos;", "'");
        result = result.replace("&lt;", "<");
        result = result.replace("&gt;", ">");
        result = result.replace("&amp;", "&");
        result = result.replace("&mdash;", "—");
        result = result.replace("&ndash;", "–");
        result = result.replace("&laquo;", "«");
        result = result.replace("&raquo;", "»");
        result = result.replace("&hellip;", "…");

        Pattern numericEntity = Pattern.compile("&#(\\d+);");
        Matcher matcher = numericEntity.matcher(result);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            try {
                int code = Integer.parseInt(matcher.group(1));
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            }
        }

        matcher.appendTail(buffer);
        result = buffer.toString();

        Pattern hexEntity = Pattern.compile("&#x([0-9a-fA-F]+);");
        matcher = hexEntity.matcher(result);
        buffer = new StringBuffer();

        while (matcher.find()) {
            try {
                int code = Integer.parseInt(matcher.group(1), 16);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(String.valueOf((char) code)));
            } catch (Exception e) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
            }
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private void startNewSession(String text) {
        stopTimer();

        originalText = text;
        tokens = tokenize(originalText);
        originalWords = extractWords(tokens);

        if (originalWords.isEmpty()) {
            Toast.makeText(this, "В загруженном тексте не найдено слов.", Toast.LENGTH_LONG).show();
            return;
        }

        hiddenWordIndexes.clear();
        savedAnswers.clear();

        hideOrder.clear();

        for (int i = 0; i < originalWords.size(); i++) {
            hideOrder.add(i);
        }

        Collections.shuffle(hideOrder, new Random());

        hideStep = 0;
        sessionActive = true;

        elapsedBeforeResumeMs = 0L;
        lastResumeRealtimeMs = SystemClock.elapsedRealtime();
        nextHideAtElapsedMs = INTERVAL;

        poemEditText.setText(originalText);

        urlPanel.setVisibility(View.GONE);

        nextStageButton.setEnabled(true);
        saveButton.setEnabled(true);
        checkButton.setEnabled(true);

        startTimer();
        updateTimerAndScoreText();

        Toast.makeText(this, "Стих загружен.", Toast.LENGTH_LONG).show();
    }

    private void goToNextStageManually() {
        if (!sessionActive || originalWords.isEmpty()) {
            Toast.makeText(this, "Сначала загрузите стих.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestNextStageWithValidation();
    }

    private void requestNextStageWithValidation() {
        if (hiddenWordIndexes.isEmpty()) {
            advanceHidingStage();
            nextHideAtElapsedMs = getCurrentElapsedMs() + INTERVAL;
            return;
        }

        if (isFinalStageReached()) {
            showCheckResult();
            return;
        }

        cacheAnswersFromCurrentText();

        StageCheckResult result = checkCurrentStage();

        if (result.missing == 0 && result.errors == 0) {
            advanceHidingStage();
            nextHideAtElapsedMs = getCurrentElapsedMs() + INTERVAL;
            return;
        }

        showStageNotCompletedDialog(result);
    }

    private void handleAutomaticStageChange() {
        if (!sessionActive || originalWords.isEmpty()) {
            return;
        }

        if (isFinalStageReached()) {
            return;
        }

        if (hiddenWordIndexes.isEmpty()) {
            advanceHidingStage();
            return;
        }

        cacheAnswersFromCurrentText();

        StageCheckResult result = checkCurrentStage();

        if (result.missing == 0 && result.errors == 0) {
            advanceHidingStage();
        } else {
            Toast.makeText(
                    MainActivity.this,
                    "Этап не завершён: есть пустые поля или ошибки.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void showStageNotCompletedDialog(StageCheckResult result) {
        String message =
                "Этот этап ещё не пройден полностью.\n\n" +
                        "Правильно: " + result.correct + " из " + result.total + "\n" +
                        "Не заполнено: " + result.missing + "\n" +
                        "Ошибок: " + result.errors + "\n\n" +
                        "Что сделать?";

        new AlertDialog.Builder(this)
                .setTitle("Этап не завершён")
                .setMessage(message)
                .setPositiveButton("Пройти этап ещё раз", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        repeatCurrentStage();
                    }
                })
                .setNegativeButton("Вернуться к исходному стиху", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        resetToOriginalPoem();
                    }
                })
                .setNeutralButton("Остаться здесь", null)
                .show();
    }

    private StageCheckResult checkCurrentStage() {
        ArrayList<String> currentUnits = extractWordsAndBlanks(poemEditText.getText().toString());

        int total = hiddenWordIndexes.size();
        int correct = 0;
        int missing = 0;
        int errors = 0;

        for (Integer hiddenIndex : hiddenWordIndexes) {
            if (hiddenIndex < 0 || hiddenIndex >= originalWords.size()) {
                errors++;
                continue;
            }

            if (hiddenIndex >= currentUnits.size()) {
                missing++;
                continue;
            }

            String actualRaw = currentUnits.get(hiddenIndex).trim();
            String expectedRaw = originalWords.get(hiddenIndex);

            if (actualRaw.isEmpty() || isBlankPlaceholder(actualRaw)) {
                missing++;
                continue;
            }

            if (normalize(actualRaw).equals(normalize(expectedRaw))) {
                correct++;
            } else {
                errors++;
            }
        }

        return new StageCheckResult(total, correct, missing, errors);
    }

    private void repeatCurrentStage() {
        ArrayList<String> currentUnits = extractWordsAndBlanks(poemEditText.getText().toString());

        for (Integer hiddenIndex : hiddenWordIndexes) {
            if (hiddenIndex < 0 || hiddenIndex >= originalWords.size()) {
                savedAnswers.remove(hiddenIndex);
                continue;
            }

            if (hiddenIndex >= currentUnits.size()) {
                savedAnswers.remove(hiddenIndex);
                continue;
            }

            String actualRaw = currentUnits.get(hiddenIndex).trim();
            String expectedRaw = originalWords.get(hiddenIndex);

            if (!actualRaw.isEmpty()
                    && !isBlankPlaceholder(actualRaw)
                    && normalize(actualRaw).equals(normalize(expectedRaw))) {
                savedAnswers.put(hiddenIndex, actualRaw);
            } else {
                savedAnswers.remove(hiddenIndex);
            }
        }

        poemEditText.setText(buildMaskedText());
        nextHideAtElapsedMs = getCurrentElapsedMs() + INTERVAL;

        Toast.makeText(this, "Повторите этот этап.", Toast.LENGTH_SHORT).show();
    }

    private void resetToOriginalPoem() {
        hiddenWordIndexes.clear();
        savedAnswers.clear();

        hideStep = 0;

        poemEditText.setText(originalText);

        nextStageButton.setEnabled(true);
        nextHideAtElapsedMs = getCurrentElapsedMs() + INTERVAL;

        updateTimerAndScoreText();

        Toast.makeText(this, "Возврат к исходному стихотворению.", Toast.LENGTH_SHORT).show();
    }

    private void advanceHidingStage() {
        if (originalWords.isEmpty()) {
            return;
        }

        cacheAnswersFromCurrentText();

        double targetPercent = Math.min(1.0, 0.10 + hideStep * 0.15);
        int targetHiddenCount = (int) Math.ceil(originalWords.size() * targetPercent);

        hiddenWordIndexes.clear();

        for (int i = 0; i < targetHiddenCount && i < hideOrder.size(); i++) {
            hiddenWordIndexes.add(hideOrder.get(i));
        }

        hideStep++;

        poemEditText.setText(buildMaskedText());
        updateTimerAndScoreText();

        if (targetPercent >= 1.0) {
            nextStageButton.setEnabled(false);
            Toast.makeText(this, "Финальный этап: скрыты все слова.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(
                    this,
                    "Скрыто примерно " + Math.round(targetPercent * 100) + "% слов.",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private boolean isFinalStageReached() {
        return !originalWords.isEmpty()
                && !hiddenWordIndexes.isEmpty()
                && hiddenWordIndexes.size() >= originalWords.size();
    }

    private String buildMaskedText() {
        StringBuilder builder = new StringBuilder();

        for (Token token : tokens) {
            if (token.isWord && hiddenWordIndexes.contains(token.wordIndex)) {
                String answer = savedAnswers.get(token.wordIndex);

                if (answer != null && !answer.trim().isEmpty() && !isBlankPlaceholder(answer)) {
                    builder.append(answer.trim());
                } else {
                    builder.append("____");
                }
            } else {
                builder.append(token.text);
            }
        }

        return builder.toString();
    }

    private void cacheAnswersFromCurrentText() {
        if (originalWords.isEmpty()) {
            return;
        }

        ArrayList<String> currentUnits = extractWordsAndBlanks(poemEditText.getText().toString());

        for (Integer hiddenIndex : hiddenWordIndexes) {
            if (hiddenIndex >= 0 && hiddenIndex < currentUnits.size()) {
                String value = currentUnits.get(hiddenIndex).trim();

                if (!value.isEmpty()) {
                    savedAnswers.put(hiddenIndex, value);
                }
            }
        }
    }

    private void showCheckResult() {
        if (originalText.trim().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Нет стиха")
                    .setMessage("Сначала загрузите стих с ilibrary.ru или culture.ru.")
                    .setPositiveButton("ОК", null)
                    .show();
            return;
        }

        cacheAnswersFromCurrentText();

        ScoreResult result = calculateScore();
        long elapsed = getCurrentElapsedMs();

        if (isFinalStageReached() && result.percent == 100) {
            showFinalSuccessDialog(elapsed, result);
            return;
        }

        String message =
                "Потраченное время: " + formatDuration(elapsed) + "\n" +
                        "Итоговый балл: " + result.percent + " из 100\n" +
                        "Правильно: " + result.correct + " из " + result.total + "\n\n" +
                        result.note;

        new AlertDialog.Builder(this)
                .setTitle("Результат проверки")
                .setMessage(message)
                .setPositiveButton("ОК", null)
                .show();

        updateTimerAndScoreText();
    }

    private void showFinalSuccessDialog(long elapsed, ScoreResult result) {
        stopTimer();
        sessionActive = false;

        String message =
                "Поздравляем! Вы выучили текст.\n\n" +
                        "Потраченное время: " + formatDuration(elapsed) + "\n" +
                        "Итоговый балл: " + result.percent + " из 100\n" +
                        "Правильно: " + result.correct + " из " + result.total;

        new AlertDialog.Builder(this)
                .setTitle("Текст выучен")
                .setMessage(message)
                .setPositiveButton("На главный экран", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        returnToMainScreen();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void returnToMainScreen() {
        stopTimer();

        originalText = "";
        tokens.clear();
        originalWords.clear();

        hideOrder.clear();
        hiddenWordIndexes.clear();
        savedAnswers.clear();

        hideStep = 0;
        sessionActive = false;

        elapsedBeforeResumeMs = 0L;
        lastResumeRealtimeMs = 0L;
        nextHideAtElapsedMs = INTERVAL;

        urlEditText.setText("");
        poemEditText.setText("Вставьте ссылку на стих с ilibrary.ru или culture.ru и нажмите «Загрузить стих».");

        urlPanel.setVisibility(View.VISIBLE);

        nextStageButton.setEnabled(false);
        saveButton.setEnabled(false);
        checkButton.setEnabled(false);
        loadInternetButton.setEnabled(true);

        updateTimerAndScoreText();

        Toast.makeText(this, "Главный экран.", Toast.LENGTH_SHORT).show();
    }

    private ScoreResult calculateScore() {
        if (hiddenWordIndexes.isEmpty()) {
            return new ScoreResult(0, 0, 100, "Пока нет скрытых слов. Нажмите «Следующий этап» или дождитесь таймера.");
        }

        ArrayList<String> currentUnits = extractWordsAndBlanks(poemEditText.getText().toString());

        int total = hiddenWordIndexes.size();
        int correct = 0;
        boolean structureWarning = currentUnits.size() != originalWords.size();

        for (Integer hiddenIndex : hiddenWordIndexes) {
            if (hiddenIndex < 0 || hiddenIndex >= originalWords.size()) {
                continue;
            }

            if (hiddenIndex >= currentUnits.size()) {
                continue;
            }

            String expected = normalize(originalWords.get(hiddenIndex));
            String actual = normalize(currentUnits.get(hiddenIndex));

            if (!isBlankPlaceholder(actual) && actual.equals(expected)) {
                correct++;
            }
        }

        int percent = total == 0 ? 100 : Math.round(correct * 100f / total);

        String note;

        if (structureWarning) {
            note = "Внимание: количество слов/пропусков изменилось. Лучше заменять каждый ____ ровно одним словом, не удаляя остальной текст.";
        } else if (hiddenWordIndexes.size() == originalWords.size() && correct == total) {
            note = "Отлично: все слова введены правильно.";
        } else {
            note = "Проверяются только скрытые слова. Видимые слова считаются подсказками.";
        }

        return new ScoreResult(total, correct, percent, note);
    }

    private void openSaveFilePicker() {
        if (originalText.trim().isEmpty()) {
            Toast.makeText(this, "Сначала загрузите стих.", Toast.LENGTH_SHORT).show();
            return;
        }

        cacheAnswersFromCurrentText();

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "poem_progress.txt");

        startActivityForResult(intent, REQUEST_SAVE_PROGRESS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_SAVE_PROGRESS) {
            return;
        }

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        saveProgressToUri(data.getData());
    }

    private void saveProgressToUri(Uri uri) {
        try {
            OutputStream outputStream = getContentResolver().openOutputStream(uri);

            if (outputStream == null) {
                Toast.makeText(this, "Не удалось создать файл.", Toast.LENGTH_LONG).show();
                return;
            }

            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)
            );

            ScoreResult score = calculateScore();

            writer.write("Прогресс заучивания стихотворения\n");
            writer.write("---------------------------------\n");
            writer.write("Источник: " + urlEditText.getText().toString() + "\n");
            writer.write("Время: " + formatDuration(getCurrentElapsedMs()) + "\n");
            writer.write("Скрыто слов: " + hiddenWordIndexes.size() + " из " + originalWords.size() + "\n");
            writer.write("Текущий балл: " + score.percent + " из 100\n");

            writer.write("\nТекущий текст:\n");
            writer.write(poemEditText.getText().toString());

            writer.write("\n\nОригинальный текст:\n");
            writer.write(originalText);
            writer.write("\n");

            writer.close();

            Toast.makeText(this, "Прогресс сохранён.", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startTimer() {
        handler.removeCallbacks(timerRunnable);
        handler.post(timerRunnable);
    }

    private void stopTimer() {
        handler.removeCallbacks(timerRunnable);
    }

    private long getCurrentElapsedMs() {
        if (!sessionActive) {
            return elapsedBeforeResumeMs;
        }

        if (lastResumeRealtimeMs == 0L) {
            return elapsedBeforeResumeMs;
        }

        return elapsedBeforeResumeMs + (SystemClock.elapsedRealtime() - lastResumeRealtimeMs);
    }

    private void updateTimerAndScoreText() {
        ScoreResult score = calculateScore();

        String text =
                "Время: " + formatDuration(getCurrentElapsedMs()) +
                        " | Балл: " + score.percent + "/100" +
                        " | Скрыто: " + hiddenWordIndexes.size() + "/" + originalWords.size();

        timerScoreTextView.setText(text);
    }

    private static ArrayList<Token> tokenize(String text) {
        ArrayList<Token> result = new ArrayList<>();

        Pattern pattern = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*");
        Matcher matcher = pattern.matcher(text);

        int lastEnd = 0;
        int wordIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                result.add(new Token(text.substring(lastEnd, matcher.start()), false, -1));
            }

            result.add(new Token(matcher.group(), true, wordIndex));
            wordIndex++;

            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            result.add(new Token(text.substring(lastEnd), false, -1));
        }

        return result;
    }

    private static ArrayList<String> extractWords(ArrayList<Token> tokens) {
        ArrayList<String> result = new ArrayList<>();

        for (Token token : tokens) {
            if (token.isWord) {
                result.add(token.text);
            }
        }

        return result;
    }

    private static ArrayList<String> extractWordsAndBlanks(String text) {
        ArrayList<String> result = new ArrayList<>();

        Pattern pattern = Pattern.compile("[\\p{L}\\p{N}]+(?:[-'’][\\p{L}\\p{N}]+)*|_+");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            result.add(matcher.group());
        }

        return result;
    }

    private static boolean isBlankPlaceholder(String value) {
        return value != null && value.trim().matches("_+");
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('ё', 'е');
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);

        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static class Token {
        final String text;
        final boolean isWord;
        final int wordIndex;

        Token(String text, boolean isWord, int wordIndex) {
            this.text = text;
            this.isWord = isWord;
            this.wordIndex = wordIndex;
        }
    }

    private static class ScoreResult {
        final int total;
        final int correct;
        final int percent;
        final String note;

        ScoreResult(int total, int correct, int percent, String note) {
            this.total = total;
            this.correct = correct;
            this.percent = percent;
            this.note = note;
        }
    }

    private static class StageCheckResult {
        final int total;
        final int correct;
        final int missing;
        final int errors;

        StageCheckResult(int total, int correct, int missing, int errors) {
            this.total = total;
            this.correct = correct;
            this.missing = missing;
            this.errors = errors;
        }
    }
}