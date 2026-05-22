package litra.mmm;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;

public class SearchWebActivity extends Activity {

    private WebView webView;
    private TextView statusTextView;
    private String lastPageText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        topBar.setBackgroundColor(Color.rgb(230, 201, 147));

        Button backButton = new Button(this);
        backButton.setText("Назад");
        backButton.setTextColor(Color.BLACK);
        backButton.setBackgroundColor(Color.WHITE);

        Button insertButton = new Button(this);
        insertButton.setText("Вставить текст страницы");
        insertButton.setTextColor(Color.BLACK);
        insertButton.setBackgroundColor(Color.WHITE);

        LinearLayout.LayoutParams smallButtonParams = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
        smallButtonParams.setMargins(0, 0, dpToPx(8), 0);

        LinearLayout.LayoutParams bigButtonParams = new LinearLayout.LayoutParams(0, dpToPx(48), 2f);

        topBar.addView(backButton, smallButtonParams);
        topBar.addView(insertButton, bigButtonParams);

        statusTextView = new TextView(this);
        statusTextView.setText("Откройте сайт со стихотворением. Лучше выделите только текст стиха, потом нажмите «Вставить текст страницы».");
        statusTextView.setTextColor(Color.BLACK);
        statusTextView.setBackgroundColor(Color.rgb(230, 201, 147));
        statusTextView.setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(8));

        webView = new WebView(this);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new PageTextBridge(), "PageTextBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                statusTextView.setText("Страница открыта. Можно выделить сам стих и нажать «Вставить текст страницы».");
                extractVisiblePageText();
            }
        });

        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        insertButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                extractVisiblePageText();
                webView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        String text = cleanExtractedPoemText(lastPageText);

                        if (text.isEmpty()) {
                            Toast.makeText(
                                    SearchWebActivity.this,
                                    "Текст страницы пока не получен. Дождитесь загрузки сайта или выделите стих вручную.",
                                    Toast.LENGTH_LONG
                            ).show();
                            return;
                        }

                        Intent result = new Intent();
                        result.putExtra("poemText", text);
                        setResult(RESULT_OK, result);
                        finish();
                    }
                }, 250);
            }
        });

        root.addView(topBar);
        root.addView(statusTextView);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);

        String query = getIntent().getStringExtra("query");

        if (query == null || query.trim().isEmpty()) {
            query = "текст стихотворения";
        }

        openSearch(query);
    }

    private void openSearch(String query) {
        try {
            String searchQuery = "текст стихотворения " + query.trim();
            String url = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(searchQuery, "UTF-8");
            webView.loadUrl(url);
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка поиска: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void extractVisiblePageText() {
        webView.loadUrl(
                "javascript:(function() {" +
                        "var selected = '';" +
                        "try { selected = window.getSelection().toString(); } catch(e) {}" +
                        "if (selected && selected.trim().length > 20) {" +
                        "  window.PageTextBridge.setText(selected);" +
                        "  return;" +
                        "}" +
                        "var clone = document.body ? document.body.cloneNode(true) : null;" +
                        "if (!clone) { window.PageTextBridge.setText(''); return; }" +
                        "var bad = clone.querySelectorAll('script, style, nav, header, footer, aside, form, button, input, textarea, select, noscript, iframe, svg, canvas');" +
                        "for (var i = 0; i < bad.length; i++) { bad[i].remove(); }" +
                        "var selectors = ['article','main','.poem','.poem-text','.poem_text','.verse','.entry-content','.post-content','.content','.text','[itemprop=articleBody]'];" +
                        "var best = '';" +
                        "for (var j = 0; j < selectors.length; j++) {" +
                        "  var el = clone.querySelector(selectors[j]);" +
                        "  if (el && el.innerText && el.innerText.trim().length > best.length) { best = el.innerText; }" +
                        "}" +
                        "if (!best || best.length < 100) {" +
                        "  var blocks = clone.querySelectorAll('p, div, section');" +
                        "  for (var k = 0; k < blocks.length; k++) {" +
                        "    var t = blocks[k].innerText || '';" +
                        "    if (t.length > best.length && t.length < 8000) best = t;" +
                        "  }" +
                        "}" +
                        "if (!best && clone.innerText) best = clone.innerText;" +
                        "window.PageTextBridge.setText(best);" +
                        "})()"
        );
    }

    private String cleanExtractedPoemText(String text) {
        if (text == null) {
            return "";
        }

        text = text.replace("\r", "\n");
        text = text.replace('\u00A0', ' ');
        text = text.replaceAll("[ \t\u000B\f]+", " ");

        String[] lines = text.split("\n");
        StringBuilder builder = new StringBuilder();

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isEmpty()) {
                continue;
            }

            String lower = line.toLowerCase();

            if (lower.contains("cookie")
                    || lower.contains("cookies")
                    || lower.contains("реклама")
                    || lower.contains("поделиться")
                    || lower.contains("комментар")
                    || lower.contains("войти")
                    || lower.contains("зарегистр")
                    || lower.contains("меню")
                    || lower.contains("главная")
                    || lower.contains("читать далее")
                    || lower.contains("похожие")
                    || lower.contains("навигация")
                    || lower.contains("privacy")
                    || lower.contains("copyright")
                    || lower.contains("условия использования")
                    || lower.contains("политика конфиденциальности")
                    || lower.startsWith("©")) {
                continue;
            }

            if (line.length() > 180) {
                continue;
            }

            builder.append(line).append("\n");
        }

        return builder.toString().trim();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class PageTextBridge {
        @JavascriptInterface
        public void setText(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lastPageText = text == null ? "" : text;
                }
            });
        }
    }
}
