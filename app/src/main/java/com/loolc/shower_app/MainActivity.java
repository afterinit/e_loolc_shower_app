package com.loolc.shower_app;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 登录后直接显示洗澡码，并在打开时重新读取有效状态。 */
public final class MainActivity extends AppCompatActivity {
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable countdownTick = this::updateCountdown;

    private View screen, page, card, home, loginForm, progressRow, codePanel;
    private ScrollView contentScroll;
    private TextView title, progressMessage, loginStatus, status, codeValue, generatedAt, countdownValue;
    private EditText account, password;
    private ImageButton themeToggle, signOut, codeVisibility, codeAction;
    private Button togglePassword, signIn;
    private SharedPreferences appearance;
    private CredentialVault vault;

    private CredentialVault.Session session;
    private boolean sessionSaved = true;
    private BathCode displayedCode;
    private ThemeReveal themeReveal;
    private boolean foreground, codeRevealed;
    private boolean authorizationRequired;
    private boolean light, signedIn, busy, startedBefore, retryRenew, statusIsError;

    enum CodeRequest {
        RESTORE, READ, REFRESH, SIGN_IN, REAUTHORIZE;

        boolean allowsCampusLogin() {
            return this == SIGN_IN || this == REAUTHORIZE;
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        screen = findViewById(R.id.screen);
        page = findViewById(R.id.page);
        card = findViewById(R.id.card);
        home = findViewById(R.id.home);
        contentScroll = findViewById(R.id.content_scroll);
        title = findViewById(R.id.title);
        ViewCompat.setAccessibilityHeading(title, true);
        loginForm = findViewById(R.id.login_form);
        progressRow = findViewById(R.id.progress_row);
        progressMessage = findViewById(R.id.progress_message);
        loginStatus = findViewById(R.id.login_status);
        status = findViewById(R.id.status);
        codePanel = findViewById(R.id.code_panel);
        codeValue = findViewById(R.id.code_value);
        generatedAt = findViewById(R.id.generated_at);
        countdownValue = findViewById(R.id.countdown_value);
        codeVisibility = findViewById(R.id.code_visibility);
        account = findViewById(R.id.account);
        password = findViewById(R.id.password);
        themeToggle = findViewById(R.id.theme_toggle);
        signOut = findViewById(R.id.sign_out);
        togglePassword = findViewById(R.id.toggle_password);
        signIn = findViewById(R.id.sign_in);
        codeAction = findViewById(R.id.code_action);

        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, windowInsets) -> {
            Insets edges = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(screen);
        page.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            setContentWidth(card, Math.min(dp(380), view.getWidth() - dp(48)));
            setContentWidth(home, Math.min(dp(520), view.getWidth()));
        });

        vault = new CredentialVault(getApplicationContext());
        appearance = getSharedPreferences("appearance", MODE_PRIVATE);
        light = appearance.getBoolean("light", false);
        applyTheme();
        themeToggle.setOnClickListener(view -> toggleTheme());
        togglePassword.setOnClickListener(view -> setPasswordVisible(password.getTransformationMethod() != null));
        signIn.setOnClickListener(view -> signIn());
        signOut.setOnClickListener(view -> confirmSignOut());
        codeVisibility.setOnClickListener(view -> toggleCodeVisibility());
        codeAction.setOnClickListener(view -> {
            if (authorizationRequired) confirmReauthorization();
            else loadCode(CodeRequest.REFRESH, null);
        });
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            signIn();
            return true;
        });
        loadCode(CodeRequest.RESTORE, null);
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (startedBefore && signedIn && !busy) loadCode(CodeRequest.READ, null);
        startedBefore = true;
    }

    @Override protected void onStop() {
        foreground = false;
        ui.removeCallbacks(countdownTick);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (themeReveal != null) themeReveal.cancel();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void signIn() {
        if (busy) return;
        String id = account.getText().toString().trim();
        String secret = password.getText().toString();
        if (id.isEmpty() || secret.isEmpty()) {
            setStatus(getString(R.string.missing_credentials), true);
            (id.isEmpty() ? account : password).requestFocus();
            return;
        }
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(password.getWindowToken(), 0);
        page.requestFocus();
        setPasswordVisible(false);
        loadCode(CodeRequest.SIGN_IN, new CredentialVault.Session(id, secret, ""));
    }

    private void loadCode(CodeRequest request, CredentialVault.Session entered) {
        if (busy) return;
        boolean renew = (request == CodeRequest.REFRESH || request == CodeRequest.REAUTHORIZE)
                && (displayedCode != null || retryRenew);
        int codeMessage = renew ? R.string.renewing_code : R.string.loading_code;
        int loadingMessage = request.allowsCampusLogin() ? R.string.signing_in : codeMessage;
        setBusy(loadingMessage);
        REQUESTS.execute(() -> {
            if (request == CodeRequest.RESTORE) session = vault.load();
            CredentialVault.Session identity = entered != null ? entered : session;
            if (identity == null) {
                finishRequest(null, false, false, null, null, false, false);
                return;
            }
            if (session != null) showSignedInWhileLoading(loadingMessage);

            BathCode code = null;
            String error = null;
            try {
                BathClient client = new BathClient();
                if (request.allowsCampusLogin()) {
                    authorizeBathSession(client, identity, codeMessage);
                } else if (!identity.hasUsableToken()) {
                    throw new BathClient.SessionExpired();
                }
                code = client.readCode(session.token, renew);
            } catch (BathClient.SessionExpired expired) {
                if (session != null && !session.token.isEmpty()) {
                    session = session.withToken("");
                    sessionSaved = vault.save(session);
                }
            } catch (BathClient.SignInRequired rejected) {
                vault.clear();
                session = null;
                error = rejected.getMessage();
            } catch (BathCode.Failure failure) {
                error = failure.getMessage();
            } catch (IOException exception) {
                error = getString(R.string.network_error);
            } catch (Exception exception) {
                Log.w("Shower", "浴室请求异常类型 " + exception.getClass().getSimpleName());
                error = getString(R.string.unknown_error);
            }
            boolean needsAuthorization = session != null && !session.hasUsableToken();
            finishRequest(code, session != null, sessionSaved, error, identity.account, renew, needsAuthorization);
        });
    }

    private void authorizeBathSession(BathClient client, CredentialVault.Session identity, int codeMessage) throws Exception {
        BathClient.SchoolSession school = client.signInToSchool(identity.account, identity.password);
        boolean firstSignIn = session == null;
        session = identity.withToken("");
        showSignedInWhileLoading(codeMessage);
        if (firstSignIn) sessionSaved = vault.save(session);
        session = session.withToken(client.openBathSession(identity.account, school));
        sessionSaved = vault.save(session);
    }

    private void showSignedInWhileLoading(int message) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            renderSignInState(true);
            progressMessage.setText(message);
        });
    }

    private void renderSignInState(boolean authenticated) {
        boolean enteringDifferentPage = signedIn != authenticated;
        signedIn = authenticated;
        card.setVisibility(authenticated ? View.GONE : View.VISIBLE);
        home.setVisibility(authenticated ? View.VISIBLE : View.GONE);
        loginForm.setVisibility(authenticated || busy ? View.GONE : View.VISIBLE);
        codeAction.setVisibility(authenticated ? View.VISIBLE : View.GONE);
        codeAction.setEnabled(!busy);
        codeAction.setAlpha(busy ? .4f : 1f);
        signOut.setVisibility(authenticated ? View.VISIBLE : View.GONE);
        signOut.setEnabled(!busy);
        if (authenticated) password.setText("");
        if (enteringDifferentPage) contentScroll.scrollTo(0, 0);
    }

    private void finishRequest(BathCode code, boolean authenticated, boolean remembered,
                               String error, String accountId, boolean renew, boolean needsAuthorization) {
        ui.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            retryRenew = renew;
            authorizationRequired = needsAuthorization;
            progressRow.setVisibility(View.GONE);
            renderSignInState(authenticated);
            if (!authenticated && accountId != null) account.setText(accountId);
            String notice = error;
            if (needsAuthorization) {
                String loginNotice = getString(R.string.authorization_required);
                notice = notice == null ? loginNotice : notice + "\n" + loginNotice;
            }
            if (authenticated && !remembered) {
                String storageNotice = getString(R.string.session_not_saved);
                notice = notice == null ? storageNotice : notice + "\n" + storageNotice;
            }
            setStatus(notice, error != null);
            int action = needsAuthorization ? R.string.reauthorize
                    : code != null ? R.string.renew_code : R.string.retry_code;
            codeAction.setContentDescription(getString(action));
            codeAction.setTooltipText(getString(action));
            if (code != null) showCode(code);
        });
    }

    private void setBusy(int message) {
        busy = true;
        clearCode();
        setStatus(signedIn ? null : getString(message), false);
        loginForm.setVisibility(View.GONE);
        codeAction.setEnabled(false);
        codeAction.setAlpha(.4f);
        signOut.setEnabled(false);
        progressMessage.setText(message);
        progressRow.setVisibility(View.VISIBLE);
    }

    private void showCode(BathCode code) {
        displayedCode = code;
        codeRevealed = false;
        renderCodeVisibility();
        generatedAt.setText(getString(R.string.generated_at, code.generatedAt));
        generatedAt.setVisibility(code.generatedAt.isEmpty() ? View.GONE : View.VISIBLE);
        codePanel.setVisibility(View.VISIBLE);
        updateCountdown();
    }

    private void toggleCodeVisibility() {
        if (displayedCode == null) return;
        if (themeReveal != null) themeReveal.cancel();
        codeRevealed = !codeRevealed;
        renderCodeVisibility();
    }

    private void renderCodeVisibility() {
        codeValue.setText(codeRevealed ? displayedCode.value : displayedCode.maskedValue());
        codeVisibility.setImageResource(codeRevealed ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        codeVisibility.setContentDescription(getString(codeRevealed ? R.string.conceal_code : R.string.reveal_code));
    }

    private void updateCountdown() {
        if (displayedCode == null) return;
        if (displayedCode.expiresAt == null) {
            countdownValue.setText(R.string.countdown_unavailable);
            return;
        }
        long remaining = displayedCode.expiresAt.toEpochMilli() - System.currentTimeMillis();
        if (remaining <= 0) {
            expireDisplayedCode();
            return;
        }
        countdownValue.setText(BathCode.formatCountdown(remaining));
        if (foreground) ui.postDelayed(countdownTick, (remaining - 1) % 1000 + 1);
    }

    private void expireDisplayedCode() {
        clearCode();
        if (authorizationRequired) {
            setStatus(getString(R.string.authorization_required), false);
            return;
        }
        setStatus(getString(R.string.code_expired), false);
        codeAction.setContentDescription(getString(R.string.renew_code));
        codeAction.setTooltipText(getString(R.string.renew_code));
    }

    private void clearCode() {
        ui.removeCallbacks(countdownTick);
        if (codeRevealed && themeReveal != null) themeReveal.cancel();
        displayedCode = null;
        codeRevealed = false;
        codeValue.setText("");
        countdownValue.setText("");
        codePanel.setVisibility(View.GONE);
    }

    private void confirmReauthorization() {
        if (busy) return;
        confirmation()
                .setTitle(R.string.reauthorize)
                .setMessage(R.string.login_session_notice)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reauthorize, (dialog, which) -> loadCode(CodeRequest.REAUTHORIZE, null))
                .show();
    }

    private void confirmSignOut() {
        if (busy) return;
        confirmation()
                .setTitle(R.string.confirm_sign_out)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.sign_out, (dialog, which) -> {
                    setBusy(R.string.signing_out);
                    REQUESTS.execute(() -> {
                        if (vault.clear()) {
                            session = null;
                            sessionSaved = true;
                            finishRequest(null, false, true, null, "", false, false);
                        } else {
                            finishRequest(null, true, false, getString(R.string.sign_out_failed), null, false,
                                    session != null && !session.hasUsableToken());
                        }
                    });
                }).show();
    }

    private AlertDialog.Builder confirmation() {
        return new AlertDialog.Builder(this, light ? androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert
                : androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert);
    }

    private void setPasswordVisible(boolean visible) {
        password.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        password.setSelection(password.length());
        togglePassword.setText(visible ? R.string.hide_password : R.string.show_password);
    }

    private void setStatus(String message, boolean error) {
        statusIsError = error;
        TextView current = signedIn ? status : loginStatus;
        TextView hidden = signedIn ? loginStatus : status;
        hidden.setVisibility(View.GONE);
        current.setText(message);
        current.setVisibility(message == null ? View.GONE : View.VISIBLE);
        styleStatus();
    }

    private void toggleTheme() {
        if (themeReveal != null) return;
        themeReveal = ThemeReveal.capture(screen, themeToggle);
        light = !light;
        appearance.edit().putBoolean("light", light).apply();
        applyTheme();
        if (themeReveal != null) themeReveal.start(() -> themeReveal = null);
    }

    @SuppressWarnings("deprecation")
    private void applyTheme() {
        int background = tone(0xFDFDFD, 0x0A0A0A);
        int surface = tone(0xFFFFFF, 0x141414);
        int ink = tone(0x111111, 0xFFFFFF);
        int secondary = tone(0x666666, 0xA3A3A3);
        int line = tone(0xD9D9D9, 0x444444);
        int hover = light ? 0x16111111 : 0x16FFFFFF;
        screen.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= 29) {
            screen.setForceDarkAllowed(false);
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat systemBars = WindowCompat.getInsetsController(getWindow(), screen);
        systemBars.setAppearanceLightStatusBars(light);
        systemBars.setAppearanceLightNavigationBars(light);

        card.setBackground(box(surface, line));
        findViewById(R.id.toolbar_rule).setBackgroundColor(line);
        title.setTextColor(ink);
        ((TextView) findViewById(R.id.login_title)).setTextColor(ink);
        codeValue.setTextColor(ink);
        countdownValue.setTextColor(ink);
        findViewById(R.id.code_surface).setBackground(box(tone(0xFCFCFC, 0x111111), line));
        findViewById(R.id.code_rule).setBackgroundColor(line);
        for (int id : new int[]{R.id.campus, R.id.account_label, R.id.password_label,
                R.id.progress_message, R.id.code_label, R.id.countdown_label, R.id.generated_at, R.id.login_session_notice}) {
            ((TextView) findViewById(id)).setTextColor(secondary);
        }
        for (EditText input : new EditText[]{account, password}) {
            input.setBackgroundTintList(null);
            input.setBackground(box(surface, line));
            input.setTextColor(ink);
            input.setHintTextColor(secondary);
        }
        signIn.setBackground(ripple(ink, light ? 0x26FFFFFF : 0x26000000));
        signIn.setTextColor(surface);
        togglePassword.setBackground(ripple(Color.TRANSPARENT, hover));
        togglePassword.setTextColor(secondary);
        for (ImageButton button : new ImageButton[]{themeToggle, signOut, codeVisibility, codeAction}) {
            button.setBackground(ripple(Color.TRANSPARENT, hover));
            button.setImageTintList(ColorStateList.valueOf(ink));
        }
        themeToggle.setImageResource(light ? R.drawable.ic_moon : R.drawable.ic_sun);
        themeToggle.setContentDescription(getString(light ? R.string.show_night_theme : R.string.show_day_theme));
        ((ProgressBar) findViewById(R.id.progress)).setIndeterminateTintList(ColorStateList.valueOf(secondary));
        styleStatus();
    }

    private void styleStatus() {
        int text = statusIsError ? tone(0x9A3D35, 0xF28D85) : tone(0x666666, 0xA3A3A3);
        int background = statusIsError ? tone(0xFFF8F7, 0x261819) : tone(0xF5F5F5, 0x1F1F1F);
        for (TextView message : new TextView[]{status, loginStatus}) {
            message.setTextColor(text);
            message.setBackgroundColor(background);
        }
    }

    private void setContentWidth(View content, int width) {
        if (width > 0 && content.getLayoutParams().width != width) {
            content.getLayoutParams().width = width;
            content.requestLayout();
        }
    }

    private GradientDrawable box(int background, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background);
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable ripple(int background, int ripple) {
        return new RippleDrawable(ColorStateList.valueOf(ripple), box(background, Color.TRANSPARENT),
                box(Color.WHITE, Color.TRANSPARENT));
    }

    private int tone(int day, int night) {
        return 0xFF000000 | (light ? day : night);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
