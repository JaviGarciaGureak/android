package com.example.fullscreenbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {

    private static final String PREFS = "config";
    private static final String KEY_URL = "url";              // versión muy antigua (migración)
    private static final String KEY_CURRENT = "current_url";  // versión antigua (migración)
    private static final String KEY_SITES = "sites";
    private static final String KEY_DEFAULT = "default_id";
    private static final String KEY_START_MODE = "start_mode"; // "hub" o "default"
    private static final String KEY_PW_SALT = "pw_salt";
    private static final String KEY_PW_HASH = "pw_hash";
    private static final String KEY_KIOSK = "kiosk_mode";

    // Contraseña inicial. Cámbiala desde Administración > Cambiar contraseña.
    private static final String DEFAULT_PASSWORD = "Gureak$1";
    private static final int PW_ITERATIONS = 20000;

    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_EXPORT = 1002;
    private static final int REQ_IMPORT = 1003;
    private static final String EXPORT_FORMAT = "navegador-hub-config";
    private static final String DEFAULT_ICON = "e:127760";

    private static final String KEY_HEADER_TITLE = "header_title";
    private static final String KEY_HEADER_SHOW = "header_show";
    private static final String HEADER_IMAGE_FILE = "header_image.png";
    private static final String DEFAULT_HEADER_TITLE = "Aplicaciones";

    // Iconos disponibles (puntos de código Unicode de emojis)
    private static final int[] EMOJIS = {
            0x1F310, 0x1F3E0, 0x1F4F7, 0x1F4F9, 0x1F4FA, 0x1F4CA, 0x1F4C8, 0x1F4C5,
            0x1F4E7, 0x1F4AC, 0x1F4C1, 0x1F4C4, 0x1F6D2, 0x1F527, 0x1F3ED, 0x1F69A,
            0x1F4E6, 0x1F465, 0x1F4BC, 0x1F512, 0x1F3B5, 0x1F3AE, 0x1F4F0, 0x1F4A1,
            0x1F4DE, 0x1F4B0, 0x1F393, 0x1F3E5, 0x1F4DD, 0x1F4D6, 0x1F680, 0x1F4BB,
            0x1F4F1, 0x2B50, 0x2705
    };

    private interface ImageCallback {
        void onImage(Bitmap bmp);
    }

    private interface AppPickCallback {
        void onPicked(String pkg, String label);
    }

    private static class Site {
        String id;
        String name;
        String url;
        String icon;
        String pkg = "";          // paquete de la app instalada (vacío = página web)
        boolean locked = false;   // bloqueada: para salir pide la contraseña
        boolean isApp() { return pkg != null && !pkg.isEmpty(); }
        Site(String id, String name, String url, String icon) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.icon = icon;
        }
    }

    private final List<Site> sites = new ArrayList<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private String defaultId;
    private boolean inHub = true;
    private boolean clearHistoryPending = false;
    private ImageCallback pendingImageCallback;

    private WebView webView;
    private FrameLayout root;
    private LinearLayout hubView;
    private GridView hubGrid;
    private TextView hubEmpty;
    private LinearLayout hubHeader;
    private TextView hubTitle;
    private ImageView hubLogo;
    private boolean pendingKeepAspect = false;
    private Site lockedSite;
    private AlertDialog pwDialog;
    private HubAdapter hubAdapter;
    private LinearLayout controls;
    private TextView settingsButton;
    private TextView homeButton;
    private AlertDialog adminDialog;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = new Runnable() {
        @Override public void run() { controls.setVisibility(View.GONE); }
    };

    // ============================================================ ciclo de vida

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
        webView.setVisibility(View.GONE);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHub();
        buildControls();
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

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if ("about:blank".equals(url)) return;
                if (clearHistoryPending) {
                    view.clearHistory();
                    clearHistoryPending = false;
                }
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
                controls.setVisibility(View.GONE);
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

        // Al tocar la página reaparecen los botones unos segundos
        webView.setOnTouchListener((v, event) -> {
            showControls();
            return false;
        });

        loadAll();
        ensurePassword();

        applyKioskPolicy();

        String mode = prefs().getString(KEY_START_MODE, "hub");
        Site def = findById(defaultId);
        if ("default".equals(mode) && def != null) {
            openSite(def);
        } else {
            showHub();
        }
        handler.postDelayed(this::updateLockTask, 600);
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
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            webView.getWebChromeClient().onHideCustomView();
        } else if (inHub) {
            if (!kioskEnabled()) super.onBackPressed();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else if (lockedSite == null) {
            showHub();
        }
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (!inHub) webView.onResume();
        if (isDeviceOwner()) updateLockTask();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME)) {
            handleHomePressed();
        }
    }

    /** Botón Inicio del sistema (si esta app es el launcher): si hay una app bloqueada, pide contraseña. */
    private void handleHomePressed() {
        if (lockedSite != null) {
            final Site s = lockedSite;
            askPassword(this::leaveLock, () -> {
                if (s.isApp()) launchApp(s);
            }, false);
        } else if (!inHub) {
            showHub();
        }
    }

    private void leaveLock() {
        lockedSite = null;
        updateLockTask();
        showHub();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(hideControls);
        root.removeAllViews();
        webView.destroy();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT) {
            handleExportResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_IMPORT) {
            handleImportResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_PICK_IMAGE) {
            ImageCallback cb = pendingImageCallback;
            pendingImageCallback = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && cb != null) {
                Bitmap bmp = pendingKeepAspect
                        ? decodeScaled(data.getData(), 128)
                        : decodeSquareIcon(data.getData());
                if (bmp != null) {
                    cb.onImage(bmp);
                } else {
                    Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // ============================================================== interfaz

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String emoji(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void buildHub() {
        hubView = new LinearLayout(this);
        hubView.setOrientation(LinearLayout.VERTICAL);
        hubView.setBackgroundColor(Color.rgb(16, 22, 36));

        // Cabecera compacta: imagen (opcional) + título
        hubHeader = new LinearLayout(this);
        hubHeader.setOrientation(LinearLayout.HORIZONTAL);
        hubHeader.setGravity(Gravity.CENTER_VERTICAL);
        hubHeader.setPadding(dp(20), dp(34), dp(20), dp(6));

        hubLogo = new ImageView(this);
        hubLogo.setAdjustViewBounds(true);
        hubLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        hubLogo.setMaxWidth(dp(120));
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        logoLp.rightMargin = dp(10);
        hubHeader.addView(hubLogo, logoLp);

        hubTitle = new TextView(this);
        hubTitle.setTextColor(Color.WHITE);
        hubTitle.setTextSize(20);
        hubTitle.setTypeface(null, Typeface.BOLD);
        hubTitle.setSingleLine(true);
        hubTitle.setEllipsize(TextUtils.TruncateAt.END);
        hubHeader.addView(hubTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hubView.addView(hubHeader);

        hubEmpty = new TextView(this);
        hubEmpty.setText("No hay aplicaciones configuradas.\nPulsa \u2699 e introduce la contraseña para añadirlas.");
        hubEmpty.setTextColor(Color.argb(180, 255, 255, 255));
        hubEmpty.setTextSize(16);
        hubEmpty.setPadding(dp(24), dp(24), dp(24), dp(24));
        hubEmpty.setVisibility(View.GONE);
        hubView.addView(hubEmpty);

        hubGrid = new GridView(this);
        hubGrid.setNumColumns(GridView.AUTO_FIT);
        hubGrid.setColumnWidth(dp(104));
        hubGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        hubGrid.setVerticalSpacing(dp(20));
        hubGrid.setHorizontalSpacing(dp(8));
        hubGrid.setPadding(dp(16), dp(16), dp(16), dp(110));
        hubGrid.setClipToPadding(false);
        hubGrid.setSelector(new ColorDrawable(Color.argb(30, 255, 255, 255)));
        hubAdapter = new HubAdapter();
        hubGrid.setAdapter(hubAdapter);
        hubGrid.setOnItemClickListener((parent, view, pos, id) -> openSite(sites.get(pos)));
        hubView.addView(hubGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(hubView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshHeader();
    }

    /** Aplica título, imagen y visibilidad de la cabecera según la configuración. */
    private void refreshHeader() {
        SharedPreferences p = prefs();
        String title = p.getString(KEY_HEADER_TITLE, DEFAULT_HEADER_TITLE).trim();
        boolean show = p.getBoolean(KEY_HEADER_SHOW, true);
        Bitmap logo = BitmapFactory.decodeFile(new File(getFilesDir(), HEADER_IMAGE_FILE).getPath());

        if (logo != null) {
            hubLogo.setImageBitmap(logo);
            hubLogo.setVisibility(View.VISIBLE);
        } else {
            hubLogo.setImageDrawable(null);
            hubLogo.setVisibility(View.GONE);
        }
        hubTitle.setText(title);
        hubTitle.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);

        boolean visible = show && (logo != null || !title.isEmpty());
        hubHeader.setVisibility(visible ? View.VISIBLE : View.GONE);
        // Sin cabecera, se deja más espacio arriba para la barra de estado / muesca
        hubGrid.setPadding(dp(16), visible ? dp(10) : dp(44), dp(16), dp(110));
        hubEmpty.setPadding(dp(24), visible ? dp(24) : dp(56), dp(24), dp(24));
    }

    private void buildControls() {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        settingsButton = circleButton("\u2699");
        homeButton = circleButton(emoji(0x1F3E0));
        controls.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(48), dp(48));
        hp.leftMargin = dp(12);
        controls.addView(homeButton, hp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.bottomMargin = dp(24);
        lp.leftMargin = dp(16);
        root.addView(controls, lp);

        settingsButton.setOnClickListener(v -> askPassword(this::showAdminDialog));
        homeButton.setOnClickListener(v -> {
            if (lockedSite != null) {
                askPassword(this::leaveLock, null, true);
            } else {
                showHub();
            }
        });
    }

    private TextView circleButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(24);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(150, 0, 0, 0));
        tv.setBackground(bg);
        return tv;
    }

    private void showControls() {
        if (customView != null) return;
        controls.setVisibility(View.VISIBLE);
        homeButton.setVisibility(inHub ? View.GONE : View.VISIBLE);
        handler.removeCallbacks(hideControls);
        if (!inHub) handler.postDelayed(hideControls, 4000);
    }

    private void showHub() {
        inHub = true;
        webView.loadUrl("about:blank");
        webView.onPause();
        webView.setVisibility(View.GONE);
        hubView.setVisibility(View.VISIBLE);
        refreshHub();
        showControls();
    }

    private void openSite(Site site) {
        if (site.isApp()) {
            launchApp(site);
            return;
        }
        inHub = false;
        lockedSite = site.locked ? site : null;
        updateLockTask();
        hubView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
        clearHistoryPending = true;
        webView.loadUrl(site.url);
        showControls();
    }

    private void refreshHub() {
        refreshHeader();
        hubAdapter.notifyDataSetChanged();
        hubEmpty.setVisibility(sites.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private class HubAdapter extends BaseAdapter {
        @Override public int getCount() { return sites.size(); }
        @Override public Object getItem(int i) { return sites.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convertView, ViewGroup parent) {
            Site s = sites.get(i);

            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(4), dp(4), dp(4), dp(4));

            FrameLayout box = new FrameLayout(MainActivity.this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(Color.argb(40, 255, 255, 255));
            box.setBackground(bg);
            box.addView(iconView(s.icon, 40, 18), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            tile.addView(box, new LinearLayout.LayoutParams(dp(72), dp(72)));

            TextView label = new TextView(MainActivity.this);
            label.setText(s.name);
            label.setTextColor(Color.WHITE);
            label.setTextSize(13);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            tile.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return tile;
        }
    }

    // ============================================================== iconos

    private View iconView(String icon, int emojiSp, int radiusDp) {
        int cp = 0x1F310;
        if (icon != null && icon.startsWith("i:")) {
            Bitmap b = loadIconBitmap(icon.substring(2));
            if (b != null) return bitmapView(b, radiusDp);
        } else if (icon != null && icon.startsWith("a:")) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(icon.substring(2));
                return drawableView(d, radiusDp);
            } catch (PackageManager.NameNotFoundException e) {
                cp = 0x1F4F1; // app no instalada en este dispositivo
            }
        } else if (icon != null && icon.startsWith("e:")) {
            try {
                cp = Integer.parseInt(icon.substring(2));
            } catch (NumberFormatException ignored) { }
        }
        TextView tv = new TextView(this);
        tv.setText(emoji(cp));
        tv.setTextSize(emojiSp);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private View bitmapView(Bitmap b, final int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    private View drawableView(Drawable d, final int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        iv.setClipToOutline(true);
        return iv;
    }

    private Bitmap loadIconBitmap(String fileName) {
        Bitmap cached = iconCache.get(fileName);
        if (cached != null) return cached;
        Bitmap b = BitmapFactory.decodeFile(new File(getFilesDir(), fileName).getPath());
        if (b != null) iconCache.put(fileName, b);
        return b;
    }

    private String saveIconBitmap(Bitmap bmp, String siteId) {
        String name = "icon_" + siteId + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            return name;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap decodeSquareIcon(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 384 && o.outHeight / (sample * 2) >= 384) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in2, null, o2);
            }
            if (b == null) return null;
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2,
                    (b.getHeight() - side) / 2, side, side);
            return Bitmap.createScaledBitmap(sq, 192, 192, true);
        } catch (Exception e) {
            return null;
        }
    }

    /** Reduce la imagen manteniendo su proporción (alto máx. maxSide, ancho máx. 4x). */
    private Bitmap decodeScaled(Uri uri, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxSide * 2 && o.outHeight / (sample * 2) >= maxSide * 2) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap b;
            try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in2, null, o2);
            }
            if (b == null) return null;
            float scale = Math.min(1f, Math.min(
                    (float) maxSide / b.getHeight(), (float) (maxSide * 4) / b.getWidth()));
            if (scale >= 1f) return b;
            return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * scale)),
                    Math.max(1, Math.round(b.getHeight() * scale)), true);
        } catch (Exception e) {
            return null;
        }
    }

    // ====================================================== exportar / importar

    private void startExport() {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/json");
        it.putExtra(Intent.EXTRA_TITLE, "navegador-config.json");
        try {
            startActivityForResult(it, REQ_EXPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No se puede abrir el selector de archivos", Toast.LENGTH_SHORT).show();
        }
    }

    private void startImport() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "No se puede abrir el selector de archivos", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] readFileBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private String fileToBase64(File f) {
        if (!f.exists()) return null;
        try {
            return Base64.encodeToString(readFileBytes(f), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Devuelve los bytes solo si el texto base64 contiene una imagen válida. */
    private byte[] decodeImageBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] data = Base64.decode(s, Base64.DEFAULT);
            if (BitmapFactory.decodeByteArray(data, 0, data.length) == null) return null;
            return data;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean writeBytes(File f, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** JSON con aplicaciones (incluidas sus imágenes), página por defecto, modo de inicio y cabecera.
     *  La contraseña NO se exporta. */
    private String buildExportJson() throws Exception {
        SharedPreferences p = prefs();
        JSONObject rootObj = new JSONObject();
        rootObj.put("format", EXPORT_FORMAT);
        rootObj.put("version", 1);
        rootObj.put("start_mode", p.getString(KEY_START_MODE, "hub"));
        if (defaultId != null) rootObj.put("default_id", defaultId);

        JSONObject header = new JSONObject();
        header.put("title", p.getString(KEY_HEADER_TITLE, DEFAULT_HEADER_TITLE));
        header.put("show", p.getBoolean(KEY_HEADER_SHOW, true));
        String headerImg = fileToBase64(new File(getFilesDir(), HEADER_IMAGE_FILE));
        if (headerImg != null) header.put("image", headerImg);
        rootObj.put("header", header);

        JSONArray arr = new JSONArray();
        for (Site s : sites) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("url", s.url);
            o.put("icon", s.icon);
            o.put("pkg", s.pkg);
            o.put("locked", s.locked);
            if (s.icon != null && s.icon.startsWith("i:")) {
                String b64 = fileToBase64(new File(getFilesDir(), s.icon.substring(2)));
                if (b64 != null) o.put("icon_image", b64);
            }
            arr.put(o);
        }
        rootObj.put("sites", arr);
        return rootObj.toString(2);
    }

    private void handleExportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            String json = buildExportJson();
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new IllegalStateException("sin salida");
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Configuración exportada", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo exportar la configuración", Toast.LENGTH_LONG).show();
        }
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("sin entrada");
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 30L * 1024 * 1024) throw new IllegalStateException("archivo demasiado grande");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void handleImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            JSONObject cfg = new JSONObject(readText(data.getData()));
            JSONArray arr = cfg.optJSONArray("sites");
            if (!EXPORT_FORMAT.equals(cfg.optString("format")) || arr == null) {
                Toast.makeText(this, "El archivo no es una configuración de esta app",
                        Toast.LENGTH_LONG).show();
                return;
            }
            showImportChoice(cfg, arr.length());
        } catch (Exception e) {
            Toast.makeText(this, "El archivo no es una configuración válida", Toast.LENGTH_LONG).show();
        }
    }

    private void showImportChoice(final JSONObject cfg, int count) {
        new AlertDialog.Builder(this)
                .setTitle("Importar configuración")
                .setMessage("El archivo contiene " + count + " aplicaciones.\n\n"
                        + "Reemplazar: sustituye las aplicaciones, la cabecera y las opciones actuales.\n\n"
                        + "Añadir: suma las aplicaciones nuevas a las que ya tienes.")
                .setPositiveButton("Reemplazar", (d, w) -> applyImport(cfg, true))
                .setNeutralButton("Añadir", (d, w) -> applyImport(cfg, false))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private boolean urlExists(String url) {
        if (url == null) return false;
        for (Site s : sites) {
            if (s.url.equalsIgnoreCase(url)) return true;
        }
        return false;
    }

    /** Crea una aplicación a partir del JSON; null si los datos no son válidos. */
    private Site importSite(JSONObject o, boolean keepId) {
        String pkg = o.optString("pkg", "");
        boolean app = pkg.matches("[A-Za-z0-9._]{1,200}");
        if (!app) pkg = "";
        String url = "";
        if (!app) {
            url = normalizeUrl(o.optString("url", ""));
            if (url == null) return null;
        }
        String name = o.optString("name", "").trim();
        if (name.isEmpty()) {
            if (app) {
                name = pkg;
            } else {
                String host = Uri.parse(url).getHost();
                name = host != null ? host : url;
            }
        }
        String id = keepId ? o.optString("id", "") : "";
        if (!id.matches("[A-Za-z0-9\\-]{1,64}") || findById(id) != null) {
            id = UUID.randomUUID().toString();
        }
        String icon = o.optString("icon", DEFAULT_ICON);
        if (icon.startsWith("i:")) {
            byte[] img = decodeImageBase64(o.optString("icon_image", ""));
            String file = "icon_" + id + ".png";
            if (img != null && writeBytes(new File(getFilesDir(), file), img)) {
                icon = "i:" + file;
            } else {
                icon = DEFAULT_ICON;
            }
        } else if (icon.startsWith("a:")) {
            icon = app ? "a:" + pkg : DEFAULT_ICON;
        } else if (!icon.startsWith("e:")) {
            icon = DEFAULT_ICON;
        }
        Site site = new Site(id, name, url, icon);
        site.pkg = pkg;
        site.locked = o.optBoolean("locked", false);
        return site;
    }

    private void applyImport(JSONObject cfg, boolean replace) {
        JSONArray arr = cfg.optJSONArray("sites");
        if (arr == null) return;

        if (replace) {
            for (Site s : sites) {
                if (s.icon != null && s.icon.startsWith("i:")) {
                    new File(getFilesDir(), s.icon.substring(2)).delete();
                }
            }
            sites.clear();
            defaultId = null;
        }

        int added = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (!replace) {
                String ipkg = o.optString("pkg", "");
                boolean dup = !ipkg.isEmpty()
                        ? pkgExists(ipkg)
                        : urlExists(normalizeUrl(o.optString("url", "")));
                if (dup) continue;
            }
            Site s = importSite(o, replace);
            if (s != null) {
                sites.add(s);
                added++;
            }
        }

        if (replace) {
            String did = cfg.optString("default_id", "");
            defaultId = findById(did) != null ? did : null;

            String mode = cfg.optString("start_mode", "hub");
            SharedPreferences.Editor e = prefs().edit()
                    .putString(KEY_START_MODE, "default".equals(mode) ? "default" : "hub");
            JSONObject h = cfg.optJSONObject("header");
            if (h != null) {
                e.putString(KEY_HEADER_TITLE, h.optString("title", DEFAULT_HEADER_TITLE));
                e.putBoolean(KEY_HEADER_SHOW, h.optBoolean("show", true));
                File hf = new File(getFilesDir(), HEADER_IMAGE_FILE);
                byte[] img = decodeImageBase64(h.optString("image", ""));
                if (img != null) {
                    writeBytes(hf, img);
                } else {
                    hf.delete();
                }
            }
            e.apply();
        }

        iconCache.clear();
        persist();
        refreshHub();
        showAdminDialog();
        Toast.makeText(this, "Importadas " + added + " aplicaciones", Toast.LENGTH_LONG).show();
    }

    // ====================================================== quiosco / apps instaladas

    private boolean kioskEnabled() {
        return prefs().getBoolean(KEY_KIOSK, false);
    }

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    }

    private ComponentName adminComponent() {
        return new ComponentName(this, AdminReceiver.class);
    }

    private boolean isDeviceOwner() {
        try {
            return dpm().isDeviceOwnerApp(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInLockTask() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean pkgExists(String pkg) {
        for (Site s : sites) {
            if (pkg.equals(s.pkg)) return true;
        }
        return false;
    }

    /** Con la app como propietaria del dispositivo: autoriza las apps del Hub en el modo quiosco,
     *  deja solo el botón Inicio y, si el quiosco está activo, fija esta app como launcher. */
    private void applyKioskPolicy() {
        if (!isDeviceOwner()) return;
        try {
            DevicePolicyManager d = dpm();
            ComponentName admin = adminComponent();
            List<String> allowed = new ArrayList<>();
            allowed.add(getPackageName());
            for (Site s : sites) {
                if (s.isApp() && !allowed.contains(s.pkg)) allowed.add(s.pkg);
            }
            d.setLockTaskPackages(admin, allowed.toArray(new String[0]));
            if (Build.VERSION.SDK_INT >= 28) {
                d.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME);
            }
            if (kioskEnabled()) {
                IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
                f.addCategory(Intent.CATEGORY_HOME);
                f.addCategory(Intent.CATEGORY_DEFAULT);
                d.addPersistentPreferredActivity(admin, f, new ComponentName(this, MainActivity.class));
            } else {
                d.clearPackagePersistentPreferredActivities(admin, getPackageName());
            }
        } catch (Exception ignored) { }
    }

    /** Entra o sale del modo de bloqueo de tareas (Lock Task) según el quiosco y la app bloqueada. */
    private void updateLockTask() {
        boolean lockedNeedsPin = lockedSite != null && (!lockedSite.isApp() || isDeviceOwner());
        boolean want = kioskEnabled() || lockedNeedsPin;
        try {
            if (want) {
                if (!isInLockTask()) startLockTask();
            } else if (isInLockTask()) {
                stopLockTask();
            }
        } catch (Exception ignored) { }
    }

    private void launchApp(Site site) {
        Intent li = getPackageManager().getLaunchIntentForPackage(site.pkg);
        if (li == null) {
            Toast.makeText(this, "La aplicación no está instalada en este dispositivo",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!isDeviceOwner() && isInLockTask()) {
            Toast.makeText(this, "Con la pantalla fijada Android no permite abrir otras aplicaciones. "
                    + "Activa el propietario del dispositivo (ver Administración).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        lockedSite = site.locked ? site : null;
        updateLockTask();
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(li);
        } catch (Exception e) {
            lockedSite = null;
            Toast.makeText(this, "No se pudo abrir la aplicación", Toast.LENGTH_LONG).show();
        }
    }

    private void showAppPicker(final AppPickCallback cb) {
        final PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        final List<ResolveInfo> apps = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (ResolveInfo item : found) {
            String p = item.activityInfo.packageName;
            if (p.equals(getPackageName()) || seen.contains(p)) continue;
            seen.add(p);
            apps.add(item);
        }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No se encontraron aplicaciones instaladas", Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(apps, (a, b) ->
                a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convertView, ViewGroup parent) {
                ResolveInfo info = apps.get(i);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(8), dp(16), dp(8));

                ImageView iv = new ImageView(MainActivity.this);
                iv.setImageDrawable(info.loadIcon(pm));
                row.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, 0, 0);
                TextView label = new TextView(MainActivity.this);
                label.setText(info.loadLabel(pm));
                label.setTextSize(16);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                TextView pkgText = new TextView(MainActivity.this);
                pkgText.setText(info.activityInfo.packageName);
                pkgText.setTextSize(11);
                pkgText.setAlpha(0.6f);
                pkgText.setSingleLine(true);
                pkgText.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(label);
                texts.addView(pkgText);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return row;
            }
        };

        new AlertDialog.Builder(this)
                .setTitle("Aplicaciones instaladas")
                .setAdapter(adapter, (d, which) -> {
                    ResolveInfo chosen = apps.get(which);
                    cb.onPicked(chosen.activityInfo.packageName, chosen.loadLabel(pm).toString());
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private void confirmRemoveOwner() {
        new AlertDialog.Builder(this)
                .setTitle("Quitar propietario del dispositivo")
                .setMessage("La app dejará de poder bloquear el dispositivo y se desactivará el modo quiosco. ¿Continuar?")
                .setPositiveButton("Quitar", (d, w) -> {
                    prefs().edit().putBoolean(KEY_KIOSK, false).apply();
                    lockedSite = null;
                    try {
                        applyKioskPolicy();
                        updateLockTask();
                        dpm().clearDeviceOwnerApp(getPackageName());
                        Toast.makeText(this, "Propietario del dispositivo eliminado",
                                Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "No se pudo quitar el propietario del dispositivo",
                                Toast.LENGTH_LONG).show();
                    }
                    showAdminDialog();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ============================================================ contraseña

    private byte[] pbkdf2(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PW_ITERATIONS, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    }

    private void setPassword(String password) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(password, salt);
            prefs().edit()
                    .putString(KEY_PW_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_PW_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
        } catch (GeneralSecurityException e) {
            Toast.makeText(this, "No se pudo guardar la contraseña", Toast.LENGTH_SHORT).show();
        }
    }

    private void ensurePassword() {
        SharedPreferences p = prefs();
        if (p.getString(KEY_PW_HASH, null) == null || p.getString(KEY_PW_SALT, null) == null) {
            setPassword(DEFAULT_PASSWORD);
        }
    }

    private boolean checkPassword(String password) {
        try {
            SharedPreferences p = prefs();
            byte[] salt = Base64.decode(p.getString(KEY_PW_SALT, ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(p.getString(KEY_PW_HASH, ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt));
        } catch (Exception e) {
            return false;
        }
    }

    private EditText passwordField(String hint) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint(hint);
        et.setSingleLine(true);
        return et;
    }

    private void askPassword(Runnable onSuccess) {
        askPassword(onSuccess, null, true);
    }

    private void askPassword(final Runnable onSuccess, final Runnable onCancel, boolean cancelable) {
        if (pwDialog != null && pwDialog.isShowing()) return;
        final EditText input = passwordField("Contraseña");
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Administración")
                .setMessage("Introduce la contraseña")
                .setView(box)
                .setCancelable(cancelable)
                .setPositiveButton("Entrar", null)
                .setNegativeButton("Cancelar", (d, w) -> {
                    if (onCancel != null) onCancel.run();
                })
                .create();
        dialog.setOnCancelListener(d -> {
            if (onCancel != null) onCancel.run();
        });
        pwDialog = dialog;
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();

        final Runnable attempt = new Runnable() {
            @Override public void run() {
                if (checkPassword(input.getText().toString())) {
                    dialog.dismiss();
                    onSuccess.run();
                } else {
                    Toast.makeText(MainActivity.this, "Contraseña incorrecta", Toast.LENGTH_SHORT).show();
                    input.setText("");
                }
            }
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> attempt.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            attempt.run();
            return true;
        });
    }

    private void showChangePasswordDialog() {
        final EditText cur = passwordField("Contraseña actual");
        final EditText n1 = passwordField("Nueva contraseña");
        final EditText n2 = passwordField("Repite la nueva contraseña");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(cur);
        box.addView(n1);
        box.addView(n2);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cambiar contraseña")
                .setView(box)
                .setPositiveButton("Cambiar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newPw = n1.getText().toString();
            if (!checkPassword(cur.getText().toString())) {
                Toast.makeText(this, "La contraseña actual no es correcta", Toast.LENGTH_SHORT).show();
            } else if (newPw.length() < 4) {
                Toast.makeText(this, "La nueva contraseña debe tener al menos 4 caracteres",
                        Toast.LENGTH_SHORT).show();
            } else if (!newPw.equals(n2.getText().toString())) {
                Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_SHORT).show();
            } else {
                setPassword(newPw);
                Toast.makeText(this, "Contraseña cambiada", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            }
        });
    }

    // ================================================================= datos

    private Site findById(String id) {
        if (id == null) return null;
        for (Site s : sites) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    private void loadAll() {
        SharedPreferences p = prefs();
        sites.clear();
        boolean changed = false;
        try {
            JSONArray arr = new JSONArray(p.getString(KEY_SITES, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.isEmpty()) {
                    id = UUID.randomUUID().toString();
                    changed = true;
                }
                String icon = o.optString("icon", "");
                if (icon.isEmpty()) {
                    icon = DEFAULT_ICON;
                    changed = true;
                }
                Site loaded = new Site(id, o.getString("name"), o.optString("url", ""), icon);
                loaded.pkg = o.optString("pkg", "");
                loaded.locked = o.optBoolean("locked", false);
                sites.add(loaded);
            }
        } catch (JSONException ignored) { }

        String legacy = p.getString(KEY_CURRENT, null);
        String veryOld = p.getString(KEY_URL, null);
        if (sites.isEmpty() && veryOld != null) {
            String host = Uri.parse(veryOld).getHost();
            sites.add(new Site(UUID.randomUUID().toString(), host != null ? host : veryOld,
                    veryOld, DEFAULT_ICON));
            legacy = veryOld;
            p.edit().remove(KEY_URL).apply();
            changed = true;
        }

        defaultId = p.getString(KEY_DEFAULT, null);
        if (legacy != null) {
            if (defaultId == null) {
                for (Site s : sites) {
                    if (s.url.equals(legacy)) {
                        defaultId = s.id;
                        p.edit().putString(KEY_START_MODE, "default").apply();
                        break;
                    }
                }
            }
            p.edit().remove(KEY_CURRENT).apply();
            changed = true;
        }
        if (defaultId != null && findById(defaultId) == null) {
            defaultId = null;
            changed = true;
        }
        if (changed) persist();
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        try {
            for (Site s : sites) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("icon", s.icon);
                o.put("pkg", s.pkg);
                o.put("locked", s.locked);
                arr.put(o);
            }
        } catch (JSONException ignored) { }
        SharedPreferences.Editor e = prefs().edit().putString(KEY_SITES, arr.toString());
        if (defaultId != null) {
            e.putString(KEY_DEFAULT, defaultId);
        } else {
            e.remove(KEY_DEFAULT);
        }
        e.apply();
        applyKioskPolicy();
    }

    private String normalizeUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (!u.contains("://")) u = "https://" + u;
        Uri uri = Uri.parse(u);
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return null;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) return null;
        return u;
    }

    // ========================================================= administración

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private GradientDrawable cellBg(boolean selected, int radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(radiusDp));
        bg.setColor(selected ? Color.argb(110, 33, 150, 243) : Color.argb(40, 128, 128, 128));
        return bg;
    }

    private void showAdminDialog() {
        if (adminDialog != null && adminDialog.isShowing()) adminDialog.dismiss();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(4), dp(20), dp(8));

        // --- Qué mostrar al abrir la app
        col.addView(sectionTitle("Al abrir la app"));
        RadioGroup rg = new RadioGroup(this);
        final RadioButton rbHub = new RadioButton(this);
        rbHub.setText("Mostrar el panel Hub");
        rbHub.setId(View.generateViewId());
        final RadioButton rbDef = new RadioButton(this);
        rbDef.setText("Abrir la página por defecto");
        rbDef.setId(View.generateViewId());
        rg.addView(rbHub);
        rg.addView(rbDef);
        boolean defMode = "default".equals(prefs().getString(KEY_START_MODE, "hub"));
        (defMode ? rbDef : rbHub).setChecked(true);
        rg.setOnCheckedChangeListener((g, checkedId) -> prefs().edit()
                .putString(KEY_START_MODE, checkedId == rbDef.getId() ? "default" : "hub")
                .apply());
        col.addView(rg);
        TextView hint = new TextView(this);
        hint.setText("Si ninguna página está marcada por defecto (\u2605), se mostrará el Hub.");
        hint.setTextSize(12);
        hint.setAlpha(0.7f);
        col.addView(hint);

        // --- Cabecera del Hub
        col.addView(sectionTitle("Cabecera del Hub"));
        CheckBox showHeader = new CheckBox(this);
        showHeader.setText("Mostrar cabecera");
        showHeader.setChecked(prefs().getBoolean(KEY_HEADER_SHOW, true));
        showHeader.setOnCheckedChangeListener((btn, checked) -> {
            prefs().edit().putBoolean(KEY_HEADER_SHOW, checked).apply();
            refreshHeader();
        });
        col.addView(showHeader);
        Button editHeader = new Button(this);
        editHeader.setText("Editar título e imagen");
        editHeader.setOnClickListener(v -> showHeaderDialog());
        col.addView(editHeader);

        // --- Lista de aplicaciones
        col.addView(sectionTitle("Aplicaciones (toca una para ver opciones)"));
        if (sites.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Todavía no hay ninguna.");
            none.setAlpha(0.7f);
            col.addView(none);
        }

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);

        for (int i = 0; i < sites.size(); i++) {
            final int idx = i;
            final Site s = sites.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(6), dp(4), dp(6));
            row.setBackgroundResource(tv.resourceId);
            row.setClickable(true);
            row.setOnClickListener(v -> showSiteOptions(idx));

            FrameLayout box = new FrameLayout(this);
            box.setBackground(cellBg(false, 10));
            box.addView(iconView(s.icon, 22, 10), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(box, new LinearLayout.LayoutParams(dp(44), dp(44)));

            TextView name = new TextView(this);
            name.setText(s.name);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(12), 0, dp(8), 0);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (s.locked) {
                TextView lockMark = new TextView(this);
                lockMark.setText(emoji(0x1F512));
                lockMark.setTextSize(16);
                lockMark.setPadding(0, 0, dp(6), 0);
                row.addView(lockMark);
            }
            if (s.id.equals(defaultId)) {
                TextView star = new TextView(this);
                star.setText("\u2605");
                star.setTextColor(Color.rgb(255, 193, 7));
                star.setTextSize(20);
                row.addView(star);
            }
            col.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        Button add = new Button(this);
        add.setText("Añadir aplicación");
        add.setOnClickListener(v -> showEditDialog(-1));
        col.addView(add);

        col.addView(sectionTitle("Modo quiosco"));
        TextView kioskStatus = new TextView(this);
        kioskStatus.setTextSize(12);
        kioskStatus.setAlpha(0.8f);
        kioskStatus.setText(isDeviceOwner()
                ? "Estado: la app es propietaria del dispositivo. El quiosco nativo bloquea Recientes, notificaciones y el cambio de aplicación."
                : "Estado: la app NO es propietaria del dispositivo, así que Android solo fijará la pantalla (se sale con Atrás + Recientes). Para el quiosco completo ejecuta por ADB:\ndpm set-device-owner "
                        + getPackageName() + "/.AdminReceiver");
        col.addView(kioskStatus);
        CheckBox kioskCheck = new CheckBox(this);
        kioskCheck.setText("Activar modo quiosco");
        kioskCheck.setChecked(kioskEnabled());
        kioskCheck.setOnCheckedChangeListener((btn, checked) -> {
            prefs().edit().putBoolean(KEY_KIOSK, checked).apply();
            applyKioskPolicy();
            updateLockTask();
        });
        col.addView(kioskCheck);
        if (isDeviceOwner()) {
            Button ownerBtn = new Button(this);
            ownerBtn.setText("Quitar propietario del dispositivo");
            ownerBtn.setOnClickListener(v -> confirmRemoveOwner());
            col.addView(ownerBtn);
        }

        col.addView(sectionTitle("Copia de seguridad / otros dispositivos"));
        Button exp = new Button(this);
        exp.setText("Exportar configuración (JSON)");
        exp.setOnClickListener(v -> startExport());
        col.addView(exp);
        Button imp = new Button(this);
        imp.setText("Importar configuración (JSON)");
        imp.setOnClickListener(v -> startImport());
        col.addView(imp);

        Button pw = new Button(this);
        pw.setText("Cambiar contraseña");
        pw.setOnClickListener(v -> showChangePasswordDialog());
        col.addView(pw);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        adminDialog = new AlertDialog.Builder(this)
                .setTitle("Administración")
                .setView(sv)
                .setPositiveButton("Cerrar", null)
                .create();
        adminDialog.setOnDismissListener(d -> refreshHub());
        adminDialog.show();
    }

    private void showSiteOptions(final int idx) {
        final Site s = sites.get(idx);
        final boolean isDef = s.id.equals(defaultId);
        String[] items = {
                "Abrir",
                "Editar",
                isDef ? "Quitar como página por defecto" : "Marcar como página por defecto",
                "Mover arriba",
                "Mover abajo",
                "Eliminar"
        };
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (adminDialog != null) adminDialog.dismiss();
                            openSite(s);
                            break;
                        case 1:
                            showEditDialog(idx);
                            break;
                        case 2:
                            defaultId = isDef ? null : s.id;
                            persist();
                            showAdminDialog();
                            break;
                        case 3:
                            if (idx > 0) {
                                Collections.swap(sites, idx, idx - 1);
                                persist();
                            }
                            showAdminDialog();
                            break;
                        case 4:
                            if (idx < sites.size() - 1) {
                                Collections.swap(sites, idx, idx + 1);
                                persist();
                            }
                            showAdminDialog();
                            break;
                        default:
                            confirmDelete(idx);
                            break;
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmDelete(final int idx) {
        final Site s = sites.get(idx);
        new AlertDialog.Builder(this)
                .setTitle("Eliminar")
                .setMessage("¿Eliminar \"" + s.name + "\"?")
                .setPositiveButton("Eliminar", (d, w) -> {
                    sites.remove(idx);
                    if (s.icon != null && s.icon.startsWith("i:")) {
                        new File(getFilesDir(), s.icon.substring(2)).delete();
                    }
                    iconCache.clear();
                    if (s.id.equals(defaultId)) defaultId = null;
                    persist();
                    showAdminDialog();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Editar el título y la imagen de la cabecera del Hub. */
    private void showHeaderDialog() {
        final SharedPreferences p = prefs();

        final EditText titleInput = new EditText(this);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setHint("Título de la cabecera");
        titleInput.setSingleLine(true);
        titleInput.setText(p.getString(KEY_HEADER_TITLE, DEFAULT_HEADER_TITLE));

        final File headerFile = new File(getFilesDir(), HEADER_IMAGE_FILE);
        final Bitmap[] img = { BitmapFactory.decodeFile(headerFile.getPath()) };
        final boolean[] changed = { false };

        final FrameLayout preview = new FrameLayout(this);
        preview.setBackground(cellBg(false, 10));
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                preview.removeAllViews();
                if (img[0] != null) {
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setImageBitmap(img[0]);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                    preview.addView(iv, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                } else {
                    TextView none = new TextView(MainActivity.this);
                    none.setText("Sin imagen");
                    none.setAlpha(0.7f);
                    none.setGravity(Gravity.CENTER);
                    preview.addView(none, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }
        };

        Button pick = new Button(this);
        pick.setText("Elegir imagen…");
        pick.setOnClickListener(v -> {
            pendingKeepAspect = true;
            pendingImageCallback = bmp -> {
                img[0] = bmp;
                changed[0] = true;
                refresh.run();
            };
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(it, REQ_PICK_IMAGE);
            } catch (Exception e) {
                Toast.makeText(this, "No se puede abrir el selector de imágenes",
                        Toast.LENGTH_SHORT).show();
            }
        });

        Button remove = new Button(this);
        remove.setText("Quitar imagen");
        remove.setOnClickListener(v -> {
            img[0] = null;
            changed[0] = true;
            refresh.run();
        });

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(pick);
        buttons.addView(remove);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(titleInput);
        box.addView(sectionTitle("Imagen junto al título"));
        box.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        box.addView(buttons);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cabecera del Hub")
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        refresh.run();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            p.edit().putString(KEY_HEADER_TITLE, titleInput.getText().toString().trim()).apply();
            if (changed[0]) {
                if (img[0] == null) {
                    headerFile.delete();
                } else {
                    try (FileOutputStream out = new FileOutputStream(headerFile)) {
                        img[0].compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception e) {
                        Toast.makeText(this, "No se pudo guardar la imagen",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
            }
            refreshHeader();
            dialog.dismiss();
        });
    }

    /** Añadir (index = -1) o editar una aplicación: nombre, URL, icono y página por defecto. */
    private void showEditDialog(final int index) {
        final Site editing = index >= 0 ? sites.get(index) : null;
        final String siteId = editing != null ? editing.id : UUID.randomUUID().toString();
        final String[] iconRef = { editing != null ? editing.icon : DEFAULT_ICON };
        final Bitmap[] newBmp = { null };
        final String[] pkgRef = { editing != null ? editing.pkg : "" };
        final boolean[] isApp = { editing != null && editing.isApp() };

        final EditText nameInput = new EditText(this);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nameInput.setHint("Nombre (p. ej. Cámara salón)");
        nameInput.setSingleLine(true);

        final EditText urlInput = new EditText(this);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setHint("https://ejemplo.com");
        urlInput.setSingleLine(true);

        if (editing != null) {
            nameInput.setText(editing.name);
            urlInput.setText(editing.url);
        }

        final RadioButton rbWeb = new RadioButton(this);
        rbWeb.setText("Página web");
        rbWeb.setId(View.generateViewId());
        final RadioButton rbApp = new RadioButton(this);
        rbApp.setText("Aplicación instalada");
        rbApp.setId(View.generateViewId());
        final RadioGroup typeGroup = new RadioGroup(this);
        typeGroup.setOrientation(LinearLayout.HORIZONTAL);
        typeGroup.addView(rbWeb);
        typeGroup.addView(rbApp);
        (isApp[0] ? rbApp : rbWeb).setChecked(true);

        final LinearLayout appBox = new LinearLayout(this);
        appBox.setOrientation(LinearLayout.VERTICAL);
        final TextView appLabel = new TextView(this);
        appLabel.setText(isApp[0] ? pkgRef[0] : "Ninguna aplicación seleccionada");
        appLabel.setPadding(0, dp(8), 0, 0);
        final Button appPick = new Button(this);
        appPick.setText("Elegir aplicación instalada…");
        appBox.addView(appLabel);
        appBox.addView(appPick);
        urlInput.setVisibility(isApp[0] ? View.GONE : View.VISIBLE);
        appBox.setVisibility(isApp[0] ? View.VISIBLE : View.GONE);
        typeGroup.setOnCheckedChangeListener((g, checkedId) -> {
            boolean appSelected = checkedId == rbApp.getId();
            isApp[0] = appSelected;
            urlInput.setVisibility(appSelected ? View.GONE : View.VISIBLE);
            appBox.setVisibility(appSelected ? View.VISIBLE : View.GONE);
        });

        final CheckBox lockedCheck = new CheckBox(this);
        lockedCheck.setText("Bloquear al abrir (para salir pide la contraseña)");
        lockedCheck.setChecked(editing != null && editing.locked);

        final CheckBox defaultCheck = new CheckBox(this);
        defaultCheck.setText("Abrir esta página por defecto al iniciar");
        defaultCheck.setChecked(editing != null ? editing.id.equals(defaultId) : defaultId == null);

        final FrameLayout preview = new FrameLayout(this);
        preview.setBackground(cellBg(false, 14));
        final TextView[] cells = new TextView[EMOJIS.length];

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                preview.removeAllViews();
                View v = newBmp[0] != null
                        ? bitmapView(newBmp[0], 14)
                        : iconView(iconRef[0], 32, 14);
                preview.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                for (int i = 0; i < cells.length; i++) {
                    boolean sel = newBmp[0] == null && iconRef[0].equals("e:" + EMOJIS[i]);
                    cells[i].setBackground(cellBg(sel, 10));
                }
            }
        };

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        for (int i = 0; i < EMOJIS.length; i++) {
            final int cp = EMOJIS[i];
            TextView c = new TextView(this);
            c.setText(emoji(cp));
            c.setTextSize(24);
            c.setGravity(Gravity.CENTER);
            c.setOnClickListener(v -> {
                iconRef[0] = "e:" + cp;
                newBmp[0] = null;
                refresh.run();
            });
            cells[i] = c;
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.width = dp(46);
            gp.height = dp(46);
            gp.setMargins(dp(3), dp(3), dp(3), dp(3));
            grid.addView(c, gp);
        }

        appPick.setOnClickListener(v -> showAppPicker((pickedPkg, pickedLabel) -> {
            pkgRef[0] = pickedPkg;
            appLabel.setText(pickedLabel + "  (" + pickedPkg + ")");
            if (nameInput.getText().toString().trim().isEmpty()) nameInput.setText(pickedLabel);
            iconRef[0] = "a:" + pickedPkg;
            newBmp[0] = null;
            refresh.run();
        }));

        Button imgBtn = new Button(this);
        imgBtn.setText("Elegir imagen…");
        imgBtn.setOnClickListener(v -> {
            pendingKeepAspect = false;
            pendingImageCallback = bmp -> {
                newBmp[0] = bmp;
                refresh.run();
            };
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("image/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(it, REQ_PICK_IMAGE);
            } catch (Exception e) {
                Toast.makeText(this, "No se puede abrir el selector de imágenes",
                        Toast.LENGTH_SHORT).show();
            }
        });

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(12);
        iconRow.addView(imgBtn, bp);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        box.addView(typeGroup);
        box.addView(nameInput);
        box.addView(urlInput);
        box.addView(appBox);
        box.addView(sectionTitle("Icono"));
        box.addView(iconRow);
        box.addView(grid);
        box.addView(defaultCheck);
        box.addView(lockedCheck);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "Editar aplicación" : "Añadir aplicación")
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton("Guardar", null)
                .setNegativeButton("Cancelar", null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
        refresh.run();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = "";
            String appPkg = "";
            if (isApp[0]) {
                appPkg = pkgRef[0] == null ? "" : pkgRef[0];
                if (appPkg.isEmpty()) {
                    Toast.makeText(this, "Elige una aplicación instalada", Toast.LENGTH_SHORT).show();
                    return;
                }
            } else {
                url = normalizeUrl(urlInput.getText().toString());
                if (url == null) {
                    Toast.makeText(this, "URL no válida (usa http o https)", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                if (isApp[0]) {
                    name = appPkg;
                } else {
                    String host = Uri.parse(url).getHost();
                    name = host != null ? host : url;
                }
            }

            String icon = iconRef[0];
            if (!isApp[0] && icon != null && icon.startsWith("a:")) icon = DEFAULT_ICON;
            if (newBmp[0] != null) {
                String file = saveIconBitmap(newBmp[0], siteId);
                if (file == null) {
                    Toast.makeText(this, "No se pudo guardar la imagen", Toast.LENGTH_SHORT).show();
                    return;
                }
                icon = "i:" + file;
            } else if (editing != null && editing.icon != null && editing.icon.startsWith("i:")
                    && !icon.equals(editing.icon)) {
                new File(getFilesDir(), editing.icon.substring(2)).delete();
            }

            if (editing != null) {
                editing.name = name;
                editing.url = url;
                editing.icon = icon;
                editing.pkg = appPkg;
                editing.locked = lockedCheck.isChecked();
            } else {
                Site created = new Site(siteId, name, url, icon);
                created.pkg = appPkg;
                created.locked = lockedCheck.isChecked();
                sites.add(created);
            }

            // Solo puede haber una página por defecto
            if (defaultCheck.isChecked()) {
                defaultId = siteId;
            } else if (siteId.equals(defaultId)) {
                defaultId = null;
            }

            iconCache.clear();
            persist();
            dialog.dismiss();
            showAdminDialog();
        });
    }
}
