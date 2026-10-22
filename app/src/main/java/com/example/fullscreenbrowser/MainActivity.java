package com.example.fullscreenbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
import java.io.InputStream;
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

    // Contraseña inicial. Cámbiala desde Administración > Cambiar contraseña.
    private static final String DEFAULT_PASSWORD = "Gureak$1";
    private static final int PW_ITERATIONS = 20000;

    private static final int REQ_PICK_IMAGE = 1001;
    private static final String DEFAULT_ICON = "e:127760";

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

    private static class Site {
        String id;
        String name;
        String url;
        String icon;
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

        String mode = prefs().getString(KEY_START_MODE, "hub");
        Site def = findById(defaultId);
        if ("default".equals(mode) && def != null) {
            openSite(def);
        } else {
            showHub();
        }
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
            super.onBackPressed();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            showHub();
        }
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }

    @Override
    protected void onResume() {
        super.onResume();
        if (!inHub) webView.onResume();
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
        if (requestCode == REQ_PICK_IMAGE) {
            ImageCallback cb = pendingImageCallback;
            pendingImageCallback = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && cb != null) {
                Bitmap bmp = decodeSquareIcon(data.getData());
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

        TextView title = new TextView(this);
        title.setText("Aplicaciones");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(dp(24), dp(48), dp(24), dp(8));
        hubView.addView(title);

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
        homeButton.setOnClickListener(v -> showHub());
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
        inHub = false;
        hubView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.onResume();
        clearHistoryPending = true;
        webView.loadUrl(site.url);
        showControls();
    }

    private void refreshHub() {
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
        if (icon != null && icon.startsWith("i:")) {
            Bitmap b = loadIconBitmap(icon.substring(2));
            if (b != null) return bitmapView(b, radiusDp);
        }
        int cp = 0x1F310;
        if (icon != null && icon.startsWith("e:")) {
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

    private void askPassword(final Runnable onSuccess) {
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
                .setPositiveButton("Entrar", null)
                .setNegativeButton("Cancelar", null)
                .create();
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
                sites.add(new Site(id, o.getString("name"), o.getString("url"), icon));
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

    /** Añadir (index = -1) o editar una aplicación: nombre, URL, icono y página por defecto. */
    private void showEditDialog(final int index) {
        final Site editing = index >= 0 ? sites.get(index) : null;
        final String siteId = editing != null ? editing.id : UUID.randomUUID().toString();
        final String[] iconRef = { editing != null ? editing.icon : DEFAULT_ICON };
        final Bitmap[] newBmp = { null };

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

        Button imgBtn = new Button(this);
        imgBtn.setText("Elegir imagen…");
        imgBtn.setOnClickListener(v -> {
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
        box.addView(nameInput);
        box.addView(urlInput);
        box.addView(sectionTitle("Icono"));
        box.addView(iconRow);
        box.addView(grid);
        box.addView(defaultCheck);

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
            String url = normalizeUrl(urlInput.getText().toString());
            if (url == null) {
                Toast.makeText(this, "URL no válida", Toast.LENGTH_SHORT).show();
                return;
            }
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) {
                String host = Uri.parse(url).getHost();
                name = host != null ? host : url;
            }

            String icon = iconRef[0];
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
            } else {
                sites.add(new Site(siteId, name, url, icon));
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
