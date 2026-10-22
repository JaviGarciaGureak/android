package com.example.fullscreenbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS = "config";
    private static final String KEY_URL = "url";          // versión antigua (migración)
    private static final String KEY_SITES = "sites";
    private static final String KEY_CURRENT = "current_url";

    private static class Site {
        String name;
        String url;
        Site(String name, String url) { this.name = name; this.url = url; }
    }

    private final List<Site> sites = new ArrayList<>();
    private String currentUrl;

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

        // Botón discreto de ajustes (esquina inferior izquierda)
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
        settingsButton.setOnClickListener(v -> showListDialog());

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

        loadSites();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            Site start = findCurrentSite();
            if (start != null) webView.loadUrl(start.url);
        }

        if (sites.isEmpty()) {
            showEditDialog(-1, false); // primer arranque: pedir nombre y URL
        } else {
            showSettingsButton();
        }
    }

    // ---------------------------------------------------------------- datos

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void loadSites() {
        SharedPreferences p = prefs();
        sites.clear();
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_SITES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                sites.add(new Site(o.getString("name"), o.getString("url")));
            }
        } catch (JSONException ignored) { }

        // Migrar la URL única de versiones anteriores
        String old = p.getString(KEY_URL, null);
        if (sites.isEmpty() && old != null) {
            String host = Uri.parse(old).getHost();
            sites.add(new Site(host != null ? host : old, old));
            saveSites();
            p.edit().putString(KEY_CURRENT, old).remove(KEY_URL).apply();
        }
        currentUrl = p.getString(KEY_CURRENT, null);
    }

    private void saveSites() {
        JSONArray arr = new JSONArray();
        try {
            for (Site s : sites) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                arr.put(o);
            }
        } catch (JSONException ignored) { }
        prefs().edit().putString(KEY_SITES, arr.toString()).apply();
    }

    private Site findCurrentSite() {
        for (Site s : sites) {
            if (s.url.equals(currentUrl)) return s;
        }
        if (!sites.isEmpty()) {
            currentUrl = sites.get(0).url;
            prefs().edit().putString(KEY_CURRENT, currentUrl).apply();
            return sites.get(0);
        }
        return null;
    }

    private void openSite(Site site) {
        currentUrl = site.url;
        prefs().edit().putString(KEY_CURRENT, currentUrl).apply();
        webView.loadUrl(site.url);
        showSettingsButton();
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

    private void showSettingsButton() {
        if (customView != null) return;
        settingsButton.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideButton);
        handler.postDelayed(hideButton, 4000);
    }

    // ------------------------------------------------------------- diálogos

    /** Lista de páginas guardadas: toca para abrir, mantén pulsado para editar o eliminar. */
    private void showListDialog() {
        if (sites.isEmpty()) {
            showEditDialog(-1, true);
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Site s : sites) {
            labels.add((s.url.equals(currentUrl) ? "\u25CF  " : "      ") + s.name);
        }
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Mis páginas")
                .setAdapter(adapter, (d, which) -> openSite(sites.get(which)))
                .setPositiveButton("Añadir", (d, w) -> showEditDialog(-1, true))
                .setNeutralButton("Recargar", (d, w) -> webView.reload())
                .setNegativeButton("Cerrar", null)
                .create();
        dlg.show();
        dlg.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            dlg.dismiss();
            showSiteOptions(pos);
            return true;
        });
    }

    private void showSiteOptions(final int index) {
        final Site site = sites.get(index);
        new AlertDialog.Builder(this)
                .setTitle(site.name)
                .setItems(new String[]{"Editar", "Eliminar"}, (d, which) -> {
                    if (which == 0) {
                        showEditDialog(index, true);
                    } else {
                        confirmDelete(index);
                    }
                })
                .setNegativeButton("Cancelar", (d, w) -> showListDialog())
                .show();
    }

    private void confirmDelete(final int index) {
        final Site site = sites.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Eliminar")
                .setMessage("¿Eliminar \"" + site.name + "\"?")
                .setPositiveButton("Eliminar", (d, w) -> {
                    sites.remove(index);
                    if (site.url.equals(currentUrl)) {
                        currentUrl = null;
                        prefs().edit().remove(KEY_CURRENT).apply();
                    }
                    saveSites();
                    showListDialog();
                })
                .setNegativeButton("Cancelar", (d, w) -> showListDialog())
                .show();
    }

    /** Añadir (index = -1) o editar una página con nombre descriptivo y URL. */
    private void showEditDialog(final int index, final boolean cancelable) {
        final EditText nameInput = new EditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameInput.setHint("Nombre (p. ej. Cámara salón)");
        nameInput.setSingleLine(true);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_NEXT);

        final EditText urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setHint("https://ejemplo.com");
        urlInput.setSingleLine(true);
        urlInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        if (index >= 0) {
            Site s = sites.get(index);
            nameInput.setText(s.name);
            urlInput.setText(s.url);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(nameInput);
        box.addView(urlInput);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "Editar página" : "Añadir página")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Guardar", null);
        if (cancelable) {
            b.setNegativeButton("Cancelar", (d, w) -> {
                if (!sites.isEmpty()) showListDialog();
            });
        }
        final AlertDialog dialog = b.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();

        final Runnable save = new Runnable() {
            @Override public void run() {
                String url = normalizeUrl(urlInput.getText().toString());
                if (url == null) {
                    Toast.makeText(MainActivity.this, "URL no válida", Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty()) {
                    String host = Uri.parse(url).getHost();
                    name = host != null ? host : url;
                }
                Site site;
                if (index >= 0) {
                    site = sites.get(index);
                    boolean wasCurrent = site.url.equals(currentUrl);
                    site.name = name;
                    site.url = url;
                    if (wasCurrent) currentUrl = url;
                } else {
                    site = new Site(name, url);
                    sites.add(site);
                }
                saveSites();
                openSite(site);
                dialog.dismiss();
            }
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.run());
        nameInput.setOnEditorActionListener((v, actionId, event) -> {
            urlInput.requestFocus();
            return true;
        });
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            save.run();
            return true;
        });
    }

    // -------------------------------------------------------- ciclo de vida

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

