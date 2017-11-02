package scott.wemessage.app.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.constraint.ConstraintLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.CycleInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import scott.wemessage.R;
import scott.wemessage.app.AppLogger;
import scott.wemessage.app.connection.ConnectionService;
import scott.wemessage.app.connection.ConnectionServiceConnection;
import scott.wemessage.app.messages.MessageDatabase;
import scott.wemessage.app.messages.objects.Account;
import scott.wemessage.app.ui.activities.ChatListActivity;
import scott.wemessage.app.ui.activities.ConversationActivity;
import scott.wemessage.app.ui.view.dialog.AnimationDialogLayout;
import scott.wemessage.app.ui.view.dialog.DialogDisplayer;
import scott.wemessage.app.ui.view.dialog.ProgressDialogLayout;
import scott.wemessage.app.ui.view.font.FontButton;
import scott.wemessage.app.utils.OnClickWaitListener;
import scott.wemessage.app.utils.view.DisplayUtils;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.crypto.BCrypt;
import scott.wemessage.commons.utils.AuthenticationUtils;
import scott.wemessage.commons.utils.StringUtils;

public class LaunchFragment extends Fragment {

    private final String TAG = "LaunchFragment";
    private final String LAUNCH_ALERT_DIALOG_TAG = "DialogLauncherAlert";
    private final String LAUNCH_ANIMATION_DIALOG_TAG = "DialogAnimationTag";

    private int oldEditTextColor;
    private int errorSnackbarDuration = 5000;
    private boolean isBoundToConnectionService = false;
    private boolean isStillConnecting = false;

    private String lastHashedPass;
    private String goToConversationHolder;

    private ConnectionServiceConnection serviceConnection = new ConnectionServiceConnection();
    private ConstraintLayout launchConstraintLayout;
    private EditText ipEditText, emailEditText, passwordEditText;
    private FontButton signInButton, offlineButton;
    private ProgressDialog loginProgressDialog;

    private BroadcastReceiver launcherBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED)) {
                unbindService();
            }else if (intent.getAction().equals(weMessage.BROADCAST_LOGIN_TIMEOUT)){
                if (loginProgressDialog != null) {
                    loginProgressDialog.dismiss();
                    generateAlertDialog(getString(R.string.timeout_alert_title), getString(R.string.timeout_alert_content)).show(getFragmentManager(), LAUNCH_ALERT_DIALOG_TAG);
                    loginProgressDialog = null;
                }
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_LOGIN_CONNECTION_ERROR)){
                if (loginProgressDialog != null) {
                    loginProgressDialog.dismiss();
                    generateAlertDialog(getString(R.string.login_error_alert_title), getString(R.string.login_connection_error_alert_content)).show(getFragmentManager(), LAUNCH_ALERT_DIALOG_TAG);
                    loginProgressDialog = null;
                }
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_LOGIN_ERROR)){
                if (loginProgressDialog != null) {
                    loginProgressDialog.dismiss();
                    generateAlertDialog(getString(R.string.login_error_alert_title), getString(R.string.login_error_alert_content)).show(getFragmentManager(), LAUNCH_ALERT_DIALOG_TAG);
                    loginProgressDialog = null;
                }
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_ALREADY_CONNECTED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_already_connected_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_INVALID_LOGIN)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_invalid_login_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_server_closed_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_ERROR)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_unknown_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_FORCED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_force_disconnect_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_client_disconnect_message));
                goToConversationHolder = null;
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_INCORRECT_VERSION)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_incorrect_version_message));
                goToConversationHolder = null;
            }else if (intent.getAction().equals(weMessage.BROADCAST_LOGIN_SUCCESSFUL)){
                if (loginProgressDialog != null){
                    loginProgressDialog.dismiss();
                    loginProgressDialog = null;
                }

                if (canStartConversationActivity()){
                    startConversationActivity(goToConversationHolder);
                }else {
                    if (intent.getBooleanExtra(weMessage.BUNDLE_FAST_CONNECT, false)) {
                        startChatListActivity();
                    }else{
                        new Handler().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                AnimationDialogFragment dialogFragment = generateAnimationDialog(R.raw.checkmark_animation);

                                dialogFragment.setDialogCompleteListener(new Runnable() {
                                    @Override
                                    public void run() {
                                        startChatListActivity();
                                    }
                                });
                                dialogFragment.show(getFragmentManager(), LAUNCH_ANIMATION_DIALOG_TAG);
                                dialogFragment.startAnimation();
                            }
                        }, 100L);
                    }
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {

        if (isServiceRunning(ConnectionService.class)) {
            bindService();
        }

        if (savedInstanceState != null){
            goToConversationHolder = savedInstanceState.getString(weMessage.BUNDLE_LAUNCHER_GO_TO_CONVERSATION_UUID);
        }else {
            if (getActivity().getIntent().getExtras() != null) {
                goToConversationHolder = getActivity().getIntent().getStringExtra(weMessage.BUNDLE_LAUNCHER_GO_TO_CONVERSATION_UUID);
            }
        }

        if (isServiceRunning(ServiceConnection.class)){
            serviceConnection.scheduleTask(new Runnable() {
                @Override
                public void run() {
                    if (serviceConnection.getConnectionService().getConnectionHandler().isConnected().get()) {
                        if (canStartConversationActivity()) {
                            startConversationActivity(goToConversationHolder);
                        } else {
                            startChatListActivity();
                        }
                    }
                }
            });
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(weMessage.BROADCAST_LOGIN_TIMEOUT);
        intentFilter.addAction(weMessage.BROADCAST_LOGIN_ERROR);
        intentFilter.addAction(weMessage.BROADCAST_LOGIN_CONNECTION_ERROR);
        intentFilter.addAction(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED);

        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_ALREADY_CONNECTED);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_INVALID_LOGIN);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_ERROR);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_FORCED);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED);
        intentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_INCORRECT_VERSION);

        intentFilter.addAction(weMessage.BROADCAST_LOGIN_SUCCESSFUL);

        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(launcherBroadcastReceiver, intentFilter);

        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(final LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_launch, container, false);

        launchConstraintLayout = view.findViewById(R.id.launchConstraintLayout);
        ipEditText = view.findViewById(R.id.launchIpEditText);
        emailEditText = view.findViewById(R.id.launchEmailEditText);
        passwordEditText = view.findViewById(R.id.launchPasswordEditText);
        signInButton = view.findViewById(R.id.signInButton);
        offlineButton = view.findViewById(R.id.offlineButton);
        oldEditTextColor = emailEditText.getCurrentTextColor();

        if (savedInstanceState != null) {
            String ipUnformatted = savedInstanceState.getString(weMessage.BUNDLE_HOST);

            lastHashedPass = savedInstanceState.getString(weMessage.BUNDLE_LAUNCHER_LAST_HASHED_PASS);
            ipEditText.setText(ipUnformatted);
            emailEditText.setText(savedInstanceState.getString(weMessage.BUNDLE_EMAIL));
            passwordEditText.setText(savedInstanceState.getString(weMessage.BUNDLE_PASSWORD));

            if (savedInstanceState.getBoolean(weMessage.BUNDLE_IS_LAUNCHER_STILL_CONNECTING)) {
                String ipAddress;
                int port;

                if (ipUnformatted.contains(":")) {
                    String[] split = ipUnformatted.split(":");

                    port = Integer.parseInt(split[1]);
                    ipAddress = split[0];
                } else {
                    ipAddress = ipUnformatted;
                    port = weMessage.DEFAULT_PORT;
                }
                showProgressDialog(view, getString(R.string.connecting_dialog_title), getString(R.string.connecting_dialog_message, ipAddress, port));
            }
        } else {
            SharedPreferences sharedPreferences = getActivity().getSharedPreferences(weMessage.APP_IDENTIFIER, Context.MODE_PRIVATE);
            String host = sharedPreferences.getString(weMessage.SHARED_PREFERENCES_LAST_HOST, "");
            String email = sharedPreferences.getString(weMessage.SHARED_PREFERENCES_LAST_EMAIL, "");
            String hashedPass = sharedPreferences.getString(weMessage.SHARED_PREFERENCES_LAST_HASHED_PASSWORD, "");

            lastHashedPass = hashedPass;

            String ipAddress;
            int port;

            if (host.contains(":")) {
                String[] split = host.split(":");

                port = Integer.parseInt(split[1]);
                ipAddress = split[0];
            } else {
                ipAddress = host;
                port = weMessage.DEFAULT_PORT;
            }

            ipEditText.setText(host);
            emailEditText.setText(email);

            if ((weMessage.get().isSignedIn() && weMessage.get().isOfflineMode())
                    || (!weMessage.get().isSignedIn() && weMessage.get().isOfflineMode()) || ((canStartConversationActivity() && weMessage.get().isOfflineMode()))) {
                if (!(getActivity().getIntent().getExtras() != null && getActivity().getIntent().getBooleanExtra(weMessage.BUNDLE_LAUNCHER_DO_NOT_TRY_RECONNECT, false))) {
                    if (!host.equals("") && !email.equals("") && !hashedPass.equals("")) {
                        startConnectionService(view, ipAddress, port, email, hashedPass, true);
                    }
                }

                if (canStartConversationActivity()) {
                    startConnectionService(view, ipAddress, port, email, hashedPass, true);
                }
            }
        }

        if (!StringUtils.isEmpty(lastHashedPass) && StringUtils.isEmpty(passwordEditText.getText().toString())){
            passwordEditText.setText(weMessage.DEFAULT_PASSWORD);
        }

        launchConstraintLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!(v instanceof EditText)) {
                    clearEditTexts();
                }
                return true;
            }
        });

        ipEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    resetEditText(ipEditText);
                }
            }
        });

        ipEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    clearEditText(ipEditText, true);
                }
                return false;
            }
        });

        emailEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    resetEditText(emailEditText);
                }
            }
        });

        emailEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    clearEditText(emailEditText, true);
                }
                return false;
            }
        });

        passwordEditText.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    if (!StringUtils.isEmpty(lastHashedPass)) {
                        lastHashedPass = null;
                        passwordEditText.setText("");
                    }
                    resetEditText(passwordEditText);
                }
            }
        });

        passwordEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    clearEditText(passwordEditText, true);
                }
                return false;
            }
        });

        view.findViewById(R.id.passwordRestoreButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                lastHashedPass = getActivity().getSharedPreferences(weMessage.APP_IDENTIFIER, Context.MODE_PRIVATE).getString(weMessage.SHARED_PREFERENCES_LAST_HASHED_PASSWORD, "");
                passwordEditText.setText(weMessage.DEFAULT_PASSWORD);
                clearEditText(passwordEditText, true);
            }
        });

        signInButton.setOnClickListener(new OnClickWaitListener(750L) {
            @Override
            public void onWaitClick(View v) {
                clearEditTexts();

                String ipUnformatted = ipEditText.getText().toString();
                String email = emailEditText.getText().toString();
                String password = passwordEditText.getText().toString();

                if (StringUtils.isEmpty(ipUnformatted)) {
                    invalidateField(ipEditText);
                    generateInvalidSnackBar(view, getString(R.string.no_ip)).show();
                    return;
                }

                String ipAddress;
                int port;

                if (ipUnformatted.contains(":")) {
                    String[] split = ipUnformatted.split(":");

                    try {
                        port = Integer.parseInt(split[1]);
                        ipAddress = split[0];
                    } catch (Exception ex) {
                        invalidateField(ipEditText);
                        generateInvalidSnackBar(view, getString(R.string.port_not_valid)).show();
                        return;
                    }
                } else {
                    ipAddress = ipUnformatted;
                    port = weMessage.DEFAULT_PORT;
                }

                if (StringUtils.isEmpty(email)) {
                    invalidateField(emailEditText);
                    generateInvalidSnackBar(view, getString(R.string.no_email)).show();
                    return;
                }

                if (!AuthenticationUtils.isValidEmailFormat(email)) {
                    invalidateField(emailEditText);
                    generateInvalidSnackBar(view, getString(R.string.invalid_email_format)).show();
                    return;
                }

                if (StringUtils.isEmpty(password)) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.no_password)).show();
                    return;
                }

                AuthenticationUtils.PasswordValidateType validateType = AuthenticationUtils.isValidPasswordFormat(password);

                if (validateType == AuthenticationUtils.PasswordValidateType.LENGTH_TOO_SMALL) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.password_too_short, weMessage.MINIMUM_PASSWORD_LENGTH)).show();
                    return;
                }

                if (validateType == AuthenticationUtils.PasswordValidateType.PASSWORD_TOO_EASY && StringUtils.isEmpty(lastHashedPass)) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.password_too_easy)).show();
                    return;
                }

                resetEditText(ipEditText);
                resetEditText(emailEditText);
                resetEditText(passwordEditText);

                float currentTextSize = DisplayUtils.convertPixelsToSp(signInButton.getTextSize(), getActivity());
                float finalTextSize = DisplayUtils.convertPixelsToSp(signInButton.getTextSize(), getActivity()) + 7;

                int currentTextColor = getResources().getColor(R.color.heavyBlue);
                int finalTextColor = getResources().getColor(R.color.superHeavyBlue);

                startTextSizeAnimation(signInButton, 0L, 150L, currentTextSize, finalTextSize);
                startTextColorAnimation(signInButton, 0L, 150L, currentTextColor, finalTextColor);

                startTextSizeAnimation(signInButton, 150L, 150L, finalTextSize, currentTextSize);
                startTextColorAnimation(signInButton, 150L, 150L, finalTextColor, currentTextColor);

                if (StringUtils.isEmpty(lastHashedPass)) {
                    startConnectionService(view, ipAddress, port, email, password, false);
                }else {
                    startConnectionService(view, ipAddress, port, email, lastHashedPass, true);
                }
            }
        });

        offlineButton.setOnClickListener(new OnClickWaitListener(750L) {
            @Override
            public void onWaitClick(View v) {
                clearEditTexts();

                String email = emailEditText.getText().toString();
                String password = passwordEditText.getText().toString();

                if (StringUtils.isEmpty(email)) {
                    invalidateField(emailEditText);
                    generateInvalidSnackBar(view, getString(R.string.no_email)).show();
                    return;
                }

                if (!AuthenticationUtils.isValidEmailFormat(email)) {
                    invalidateField(emailEditText);
                    generateInvalidSnackBar(view, getString(R.string.invalid_email_format)).show();
                    return;
                }

                if (weMessage.get().hasRecentSession() && weMessage.get().getCurrentAccount() != null
                        && weMessage.get().getCurrentAccount().getEmail().equalsIgnoreCase(emailEditText.getText().toString())){
                    startChatListActivity();
                    return;
                }

                if (StringUtils.isEmpty(password)) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.no_password)).show();
                    return;
                }

                AuthenticationUtils.PasswordValidateType validateType = AuthenticationUtils.isValidPasswordFormat(password);

                if (validateType == AuthenticationUtils.PasswordValidateType.LENGTH_TOO_SMALL) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.password_too_short, weMessage.MINIMUM_PASSWORD_LENGTH)).show();
                    return;
                }

                if (validateType == AuthenticationUtils.PasswordValidateType.PASSWORD_TOO_EASY && StringUtils.isEmpty(lastHashedPass)) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.password_too_easy)).show();
                    return;
                }

                if (validateType == AuthenticationUtils.PasswordValidateType.PASSWORD_TOO_EASY && !StringUtils.isEmpty(lastHashedPass)) {
                    invalidateField(passwordEditText);
                    generateInvalidSnackBar(view, getString(R.string.offline_mode_force_password)).show();
                    return;
                }

                resetEditText(emailEditText);
                resetEditText(passwordEditText);

                int currentTextColor = getResources().getColor(R.color.brightRed);
                int finalTextColor = getResources().getColor(R.color.brightRedTextPressed);

                startTextColorAnimation(offlineButton, 0L, 75L, currentTextColor, finalTextColor);
                startTextColorAnimation(offlineButton, 75L, 75L, finalTextColor, currentTextColor);

                signInOffline(email, password);
            }
        });
        return view;
    }

    @Override
    public void onPause() {
        isStillConnecting = loginProgressDialog != null;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (isStillConnecting){
            String ipUnformatted = ipEditText.getText().toString();
            String ipAddress;
            int port;

            if (ipUnformatted.contains(":")) {
                String[] split = ipUnformatted.split(":");

                port = Integer.parseInt(split[1]);
                ipAddress = split[0];
            } else {
                ipAddress = ipUnformatted;
                port = weMessage.DEFAULT_PORT;
            }
            showProgressDialog(getView(), getString(R.string.connecting_dialog_title), getString(R.string.connecting_dialog_message, ipAddress, port));
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(weMessage.BUNDLE_HOST, ipEditText.getText().toString());
        outState.putString(weMessage.BUNDLE_EMAIL, emailEditText.getText().toString());
        outState.putString(weMessage.BUNDLE_PASSWORD, passwordEditText.getText().toString());
        outState.putString(weMessage.BUNDLE_LAUNCHER_LAST_HASHED_PASS, lastHashedPass);
        outState.putString(weMessage.BUNDLE_LAUNCHER_GO_TO_CONVERSATION_UUID, goToConversationHolder);
        outState.putBoolean(weMessage.BUNDLE_IS_LAUNCHER_STILL_CONNECTING, loginProgressDialog != null);

        if (loginProgressDialog != null){
            loginProgressDialog.dismiss();
            loginProgressDialog = null;
        }
    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(launcherBroadcastReceiver);
        if (isBoundToConnectionService) {
            unbindService();
        }
        super.onDestroy();
    }

    private void bindService(){
        Intent intent = new Intent(getActivity(), ConnectionService.class);
        getActivity().bindService(intent, serviceConnection, Context.BIND_IMPORTANT);
        isBoundToConnectionService = true;
    }

    private void unbindService(){
        if (isBoundToConnectionService) {
            getActivity().unbindService(serviceConnection);
            isBoundToConnectionService = false;
        }
    }

    private void startConnectionService(View view, String ipAddress, int port, String email, String password, boolean alreadyHashed){
        if (isServiceRunning(ConnectionService.class)){
            AppLogger.log(AppLogger.Level.ERROR, TAG, "The connection service is already running");
            return;
        }

        Intent startServiceIntent = new Intent(getActivity(), ConnectionService.class);
        startServiceIntent.putExtra(weMessage.ARG_HOST, ipAddress);
        startServiceIntent.putExtra(weMessage.ARG_PORT, port);
        startServiceIntent.putExtra(weMessage.ARG_EMAIL, email);
        startServiceIntent.putExtra(weMessage.ARG_PASSWORD, password);
        startServiceIntent.putExtra(weMessage.ARG_PASSWORD_ALREADY_HASHED, alreadyHashed);

        getActivity().startService(startServiceIntent);
        bindService();

        showProgressDialog(view, getString(R.string.connecting_dialog_title), getString(R.string.connecting_dialog_message, ipAddress, port));
    }

    private void startChatListActivity(){
        Intent chatListIntent = new Intent(weMessage.get(), ChatListActivity.class);

        startActivity(chatListIntent);
        getActivity().finish();
    }

    private void startConversationActivity(String chatId){
        if (!StringUtils.isEmpty(chatId)) {
            Intent launcherIntent = new Intent(weMessage.get(), ConversationActivity.class);

            launcherIntent.putExtra(weMessage.BUNDLE_RETURN_POINT, ChatListActivity.class.getName());
            launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatId);

            startActivity(launcherIntent);
            getActivity().finish();
        }
    }

    private void signInOffline(String email, String password){
        weMessage.get().signOut();

        MessageDatabase database = weMessage.get().getMessageDatabase();

        if (database.getAccounts().isEmpty()){
            invalidateField(emailEditText);
            generateInvalidSnackBar(getView(), getString(R.string.offline_mode_no_accounts)).show();
            return;
        }

        Account account = database.getAccountByEmail(email);

        if (account == null){
            invalidateField(emailEditText);
            generateInvalidSnackBar(getView(), getString(R.string.offline_mode_no_email, email)).show();
            return;
        }

        if (!BCrypt.checkPassword(password, account.getEncryptedPassword())){
            invalidateField(passwordEditText);
            generateInvalidSnackBar(getView(), getString(R.string.offline_mode_incorrect_password)).show();
            return;
        }

        SharedPreferences.Editor editor = getActivity().getSharedPreferences(weMessage.APP_IDENTIFIER, Context.MODE_PRIVATE).edit();
        editor.putString(weMessage.SHARED_PREFERENCES_LAST_EMAIL, email);
        editor.putString(weMessage.SHARED_PREFERENCES_LAST_HASHED_PASSWORD, password);

        editor.apply();

        weMessage.get().setCurrentAccount(account);
        weMessage.get().signIn();
        weMessage.get().setOfflineMode(true);

        startChatListActivity();
    }

    private void invalidateField(final EditText editText){
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), getResources().getColor(R.color.colorHeader), getResources().getColor(R.color.invalidRed));
        colorAnimation.setDuration(200);
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                editText.getBackground().setColorFilter((int) animation.getAnimatedValue(), PorterDuff.Mode.SRC_ATOP);
                editText.setTextColor((int) animation.getAnimatedValue());
            }
        });

        Animation invalidShake = AnimationUtils.loadAnimation(getActivity(), R.anim.invalid_shake);
        invalidShake.setInterpolator(new CycleInterpolator(7F));

        colorAnimation.start();
        editText.startAnimation(invalidShake);
    }

    private void resetEditText( EditText editText){
        editText.getBackground().setColorFilter(getResources().getColor(R.color.colorHeader), PorterDuff.Mode.SRC_ATOP);
        editText.setTextColor(oldEditTextColor);
    }

    private void clearEditTexts() {
        closeKeyboard();
        clearEditText(ipEditText, false);
        clearEditText(emailEditText, false);
        clearEditText(passwordEditText, false);
    }

    private void clearEditText(final EditText editText, boolean closeKeyboard){
        if (closeKeyboard) {
            closeKeyboard();
        }

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                editText.clearFocus();
            }
        }, 100);
    }

    private void closeKeyboard(){
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (getActivity().getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void startTextSizeAnimation(final TextView view, long startDelay, long duration, float startSize, float endSize){
        ValueAnimator textSizeAnimator = ValueAnimator.ofFloat(startSize, endSize);
        textSizeAnimator.setDuration(duration);
        textSizeAnimator.setStartDelay(startDelay);

        textSizeAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                view.setTextSize((float) valueAnimator.getAnimatedValue());
            }
        });
        textSizeAnimator.start();
    }

    private void startTextColorAnimation(final TextView view, long startDelay, long duration, int startColor, int endColor){
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, endColor);
        colorAnimation.setDuration(duration);
        colorAnimation.setStartDelay(startDelay);

        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                view.setTextColor((int) animation.getAnimatedValue());
            }
        });
        colorAnimation.start();
    }

    private AnimationDialogFragment generateAnimationDialog(int animationSource){
        Bundle bundle = new Bundle();
        AnimationDialogFragment dialog = new AnimationDialogFragment();

        bundle.putInt(weMessage.BUNDLE_DIALOG_ANIMATION, animationSource);
        dialog.setArguments(bundle);

        return dialog;
    }

    private Snackbar generateInvalidSnackBar(View view, String message){
        final Snackbar snackbar = Snackbar.make(view, message, errorSnackbarDuration);

        snackbar.setAction(getString(R.string.dismiss_button), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                snackbar.dismiss();
            }
        });
        snackbar.setActionTextColor(getResources().getColor(R.color.brightRedText));

        View snackbarView = snackbar.getView();
        TextView textView = snackbarView.findViewById(android.support.design.R.id.snackbar_text);
        textView.setMaxLines(5);

        return snackbar;
    }

    private void showProgressDialog(final View view, String title, String message){
        final ProgressDialog progressDialog = new ProgressDialog(getActivity());
        ProgressDialogLayout progressDialogLayout = (ProgressDialogLayout) getActivity().getLayoutInflater().inflate(R.layout.progress_dialog_layout, null);

        progressDialog.setCancelable(false);

        progressDialogLayout.setTitle(title);
        progressDialogLayout.setMessage(message);
        progressDialogLayout.setButton(getString(R.string.word_cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                serviceConnection.scheduleTask(new Runnable() {
                    @Override
                    public void run() {
                        serviceConnection.getConnectionService().endService();
                    }
                });
                progressDialog.dismiss();
                generateInvalidSnackBar(view, getString(R.string.connection_cancelled)).show();
                loginProgressDialog = null;
            }
        });
        progressDialog.show();
        progressDialog.setContentView(progressDialogLayout);
        loginProgressDialog = progressDialog;
    }

    public static DialogDisplayer.AlertDialogFragment generateAlertDialog(String title, String message){
        return DialogDisplayer.generateAlertDialog(title, message);
    }

    private void showDisconnectReasonDialog(Intent bundledIntent, String defaultMessage){
        if (loginProgressDialog != null){
            loginProgressDialog.dismiss();
            loginProgressDialog = null;
        }
        DialogDisplayer.showDisconnectReasonDialog(getContext(), getFragmentManager(), bundledIntent, defaultMessage);
    }

    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) getActivity().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean canStartConversationActivity(){
        return !StringUtils.isEmpty(goToConversationHolder);
    }

    public static class AnimationDialogFragment extends DialogFragment {

        private Runnable runnable;
        private AnimationDialogLayout dialogLayout;

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Bundle args = getArguments();
            int animationResource = args.getInt(weMessage.BUNDLE_DIALOG_ANIMATION);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            final AnimationDialogLayout animationDialogLayout = (AnimationDialogLayout) getActivity().getLayoutInflater().inflate(R.layout.animation_dialog_layout, null);
            animationDialogLayout.setAnimationSource(animationResource);

            builder.setView(animationDialogLayout);

            final AlertDialog dialog = builder.create();

            animationDialogLayout.getVideoView().setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    animationDialogLayout.getVideoView().setZOrderOnTop(false);
                    dialog.dismiss();
                    if (runnable != null) {
                        new Handler().postDelayed(runnable, 100L);
                    }
                }
            });
            setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            this.dialogLayout = animationDialogLayout;

            return dialog;
        }

        public void setDialogCompleteListener(Runnable runnable){
            this.runnable = runnable;
        }

        public void startAnimation(){
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    dialogLayout.getVideoView().setZOrderOnTop(true);
                    dialogLayout.startAnimation();
                }
            }, 10);
        }

        @Override
        public void show(FragmentManager manager, String tag) {
            try {
                super.show(manager, tag);
            }catch(Exception ex){
                AppLogger.log(AppLogger.Level.ERROR, null, "Attempted to show a dialog when display was exited.");
            }
        }
    }
}