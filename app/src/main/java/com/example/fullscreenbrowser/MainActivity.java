package com.example.fullscreenbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String PREFS = "config";
    private static final String KEY_URL = "url";

    private WebView webView;
    private FrameLayout root;
    private TextView settingsButton;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideButton = new Runnable() {
        @Override public void run() { settingsButton.setVisibility(View.GONE); }
    };

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Botón discreto de ajustes (esquina superior derecha)
        settingsButton = new TextView(this);
        settingsButton.setText("\u2699");
        settingsButton.setTextColor(Color.WHITE);
        settingsButton.setTextSize(24);
        settingsButton.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(150, 0, 0, 0));
        settingsButton.setBackground(bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(48));
         lp.gravity = Gravity.BOTTOM | Gravity.START;
          lp.bottomMargin = dp(24);
           lp.leftMargin = dp(16);
        root.addView(settingsButton, lp);
        settingsButton.setOnClickListener(v -> showUrlDialog(true));

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // los enlaces se abren dentro de la app
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                settingsButton.setVisibility(View.GONE);
                root.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) customViewCallback.onCustomViewHidden();
                webView.setVisibility(View.VISIBLE);
            }
        });

        // Al tocar la pantalla reaparece el botón de ajustes unos segundos
        webView.setOnTouchListener((v, event) -> {
            showSettingsButton();
            return false;
        });

        String saved = getSavedUrl();
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (saved != null) {
            webView.loadUrl(saved);
        }

        if (saved == null) {
            showUrlDialog(false); // primer arranque: pedir la URL
        } else {
            showSettingsButton();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showSettingsButton() {
        if (customView != null) return;
        settingsButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideButton);
        handler.postDelayed(hideButton, 4000);
    }

    private String getSavedUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString(KEY_URL, null);
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (!u.contains("://")) u = "https://" + u;
        Uri uri = Uri.parse(u);
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        return u;
    }

    private void showUrlDialog(final boolean cancelable) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://ejemplo.com");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        String current = getSavedUrl();
        if (current != null) {
            input.setText(current);
            input.setSelection(current.length());
        }

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.leftMargin = dp(20);
        ip.rightMargin = dp(20);
        container.addView(input, ip);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(cancelable ? "Cambiar URL" : "Introduce la URL")
                .setView(container)
                .setCancelable(cancelable)
                .setPositiveButton("Guardar", null);
        if (cancelable) {
            b.setNegativeButton("Cancelar", null);
            b.setNeutralButton("Recargar", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { webView.reload(); }
            });
        }
        final AlertDialog dialog = b.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();

        final Runnable save = new Runnable() {
            @Override public void run() {
                String url = normalizeUrl(input.getText().toString());
                if (url == null) {
                    Toast.makeText(MainActivity.this, "URL no válida", Toast.LENGTH_SHORT).show();
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                webView.loadUrl(url);
                dialog.dismiss();
                showSettingsButton();
            }
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> save.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            save.run();
            return true;
        });
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideButton);
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }
}
