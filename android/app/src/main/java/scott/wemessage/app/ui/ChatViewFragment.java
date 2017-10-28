package scott.wemessage.app.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.afollestad.materialcamera.MaterialCamera;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.daimajia.swipe.SwipeLayout;
import com.flipboard.bottomsheet.BottomSheetLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import scott.wemessage.R;
import scott.wemessage.app.AppLogger;
import scott.wemessage.app.connection.ConnectionService;
import scott.wemessage.app.connection.ConnectionServiceConnection;
import scott.wemessage.app.messages.MessageCallbacks;
import scott.wemessage.app.messages.MessageManager;
import scott.wemessage.app.messages.objects.ActionMessage;
import scott.wemessage.app.messages.objects.Attachment;
import scott.wemessage.app.messages.objects.Contact;
import scott.wemessage.app.messages.objects.Message;
import scott.wemessage.app.messages.objects.MessageBase;
import scott.wemessage.app.messages.objects.chats.Chat;
import scott.wemessage.app.messages.objects.chats.GroupChat;
import scott.wemessage.app.ui.activities.ChatAddContactActivity;
import scott.wemessage.app.ui.activities.ChatListActivity;
import scott.wemessage.app.ui.activities.ContactViewActivity;
import scott.wemessage.app.ui.activities.ConversationActivity;
import scott.wemessage.app.ui.activities.LaunchActivity;
import scott.wemessage.app.ui.activities.MessageImageActivity;
import scott.wemessage.app.ui.activities.MessageVideoActivity;
import scott.wemessage.app.ui.view.dialog.DialogDisplayer;
import scott.wemessage.app.utils.AndroidUtils;
import scott.wemessage.app.utils.FileLocationContainer;
import scott.wemessage.app.utils.IOUtils;
import scott.wemessage.app.utils.OnClickWaitListener;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.connection.json.action.JSONAction;
import scott.wemessage.commons.connection.json.message.JSONMessage;
import scott.wemessage.commons.types.FailReason;
import scott.wemessage.commons.types.MimeType;
import scott.wemessage.commons.types.ReturnType;
import scott.wemessage.commons.utils.FileUtils;
import scott.wemessage.commons.utils.StringUtils;

public class ChatViewFragment extends MessagingFragment implements MessageCallbacks {

    private final int ERROR_SNACKBAR_DURATION = 5;
    private final int TYPE_HEADER = 0;
    private final int TYPE_CONTACT = 1;
    private final int TYPE_CONTACT_ATTACHMENT = 2;
    private final int TYPE_ATTACHMENT = 3;

    private String BUNDLE_IS_IN_EDIT_MODE = "bundleIsInEditMode";
    private String BUNDLE_EDITED_NAME = "bundleEditedName";
    private String BUNDLE_IS_CHOOSE_PHOTO_LAYOUT_SHOWN = "bundleIsChoosePhotoLayoutShown";
    private String BUNDLE_EDITED_PICTURE = "bundleEditedPicture";

    private boolean isBoundToConnectionService = false;
    private boolean isInEditMode = false;
    private boolean isChoosePhotoLayoutShown = false;

    private String chatUuid;
    private String callbackUuid;
    private String editedName;
    private String editedChatPicture;

    private RecyclerView chatViewRecyclerView;
    private ChatViewAdapter chatViewAdapter;
    private BottomSheetLayout bottomSheetLayout;

    private RelativeLayout chatViewChoosePhotoLayout;
    private RecyclerView chatViewChoosePhotoRecyclerView;
    private TextView chatViewChoosePhotoErrorTextView;
    private ChoosePhotoAdapter choosePhotoAdapter;

    private ImageButton toolbarBackButton;
    private Button toolbarEditButton;
    private Button toolbarCancelButton;

    private ConnectionServiceConnection serviceConnection = new ConnectionServiceConnection();

    private BroadcastReceiver chatViewBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED)){
                unbindService();
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_server_closed_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_ERROR)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_unknown_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_FORCED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_force_disconnect_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED)){
                showDisconnectReasonDialog(intent, getString(R.string.connection_error_client_disconnect_message), new Runnable() {
                    @Override
                    public void run() {
                        goToLauncher();
                    }
                });
            }else if(intent.getAction().equals(weMessage.BROADCAST_NEW_MESSAGE_ERROR)){
                showErroredSnackBar(getString(R.string.new_message_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_SEND_MESSAGE_ERROR)){
                showErroredSnackBar(getString(R.string.send_message_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR)) {
                showErroredSnackBar(getString(R.string.message_update_error));
            }else if(intent.getAction().equals(weMessage.BROADCAST_ACTION_PERFORM_ERROR)){
                if (intent.getExtras() != null){
                    showErroredSnackBar(intent.getStringExtra(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE));
                }else {
                    showErroredSnackBar(getString(R.string.action_perform_error_default));
                }
            }else if(intent.getAction().equals(weMessage.BROADCAST_RESULT_PROCESS_ERROR)){
                showErroredSnackBar(getString(R.string.result_process_error));
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (isServiceRunning(ConnectionService.class)){
            bindService();
        }

        MessageManager messageManager = weMessage.get().getMessageManager();
        IntentFilter broadcastIntentFilter = new IntentFilter();

        broadcastIntentFilter.addAction(weMessage.BROADCAST_CONNECTION_SERVICE_STOPPED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_FORCED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_NEW_MESSAGE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_SEND_MESSAGE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_ACTION_PERFORM_ERROR);
        broadcastIntentFilter.addAction(weMessage.BROADCAST_RESULT_PROCESS_ERROR);

        callbackUuid = UUID.randomUUID().toString();
        messageManager.hookCallbacks(callbackUuid, this);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(chatViewBroadcastReceiver, broadcastIntentFilter);

        if (savedInstanceState == null) {
            Intent startingIntent = getActivity().getIntent();

            chatUuid = startingIntent.getStringExtra(weMessage.BUNDLE_CONVERSATION_CHAT);
            editedName = ((GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid)).getDisplayName();
        }else {
            isInEditMode = savedInstanceState.getBoolean(BUNDLE_IS_IN_EDIT_MODE, isInEditMode);

            chatUuid = savedInstanceState.getString(weMessage.BUNDLE_CONVERSATION_CHAT);
            editedName = savedInstanceState.getString(BUNDLE_EDITED_NAME);
            editedChatPicture = savedInstanceState.getString(BUNDLE_EDITED_PICTURE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.fragment_chat_view, container, false);

        Toolbar toolbar = getActivity().findViewById(R.id.chatViewToolbar);
        toolbarBackButton = toolbar.findViewById(R.id.chatViewBackButton);
        toolbarEditButton = toolbar.findViewById(R.id.chatViewEditButton);
        toolbarCancelButton = toolbar.findViewById(R.id.chatViewCancelButton);

        toolbarBackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isInEditMode) {
                    returnToConversationScreen();
                }
            }
        });

        toolbarCancelButton.setOnClickListener(new OnClickWaitListener(500L) {
            @Override
            public void onWaitClick(View view) {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }
            }
        });

        toolbarEditButton.setOnClickListener(new OnClickWaitListener(500L) {
            @Override
            public void onWaitClick(View view) {
                toggleEditMode(!isInEditMode, true);
            }
        });

        toolbar.setTitle(null);
        ((AppCompatActivity)getActivity()).setSupportActionBar(toolbar);

        chatViewRecyclerView = view.findViewById(R.id.chatViewRecyclerView);
        bottomSheetLayout = view.findViewById(R.id.chatViewBottomSheetLayout);
        chatViewChoosePhotoLayout = view.findViewById(R.id.chatViewChoosePhotoLayout);
        chatViewChoosePhotoRecyclerView = view.findViewById(R.id.chatViewChoosePhotoRecyclerView);
        chatViewChoosePhotoErrorTextView = view.findViewById(R.id.chatViewChoosePhotoErrorTextView);

        GridLayoutManager layoutManager = new GridLayoutManager(getActivity(), 2);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                switch(chatViewAdapter.getItemViewType(position)){
                    case TYPE_HEADER:
                        return 2;
                    case TYPE_CONTACT:
                        return 2;
                    case TYPE_CONTACT_ATTACHMENT:
                        return 2;
                    default:
                        return 1;
                }
            }
        });

        chatViewAdapter = new ChatViewAdapter();

        chatViewRecyclerView.setLayoutManager(layoutManager);
        chatViewRecyclerView.setAdapter(chatViewAdapter);

        ViewGroup.LayoutParams layoutParams = chatViewChoosePhotoLayout.getLayoutParams();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        layoutParams.height = displayMetrics.heightPixels / 2;

        chatViewChoosePhotoLayout.setLayoutParams(layoutParams);
        chatViewChoosePhotoRecyclerView.setLayoutManager(new GridLayoutManager(getActivity(), 2, GridLayoutManager.VERTICAL, false));

        chatViewRecyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (isChoosePhotoLayoutShown){
                    toggleChoosePhotoLayout(false);
                }
                return false;
            }
        });
        GroupChat groupChat = (GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid);

        chatViewAdapter.loadChat(groupChat);
        toggleIsInChat(groupChat.isInChat(), false);

        if (isInEditMode){
            toggleEditMode(true, false);
        }

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(BUNDLE_IS_CHOOSE_PHOTO_LAYOUT_SHOWN)) {
                toggleChoosePhotoLayout(true);
            }
        }

        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == weMessage.REQUEST_CODE_CAMERA){
            if (resultCode == Activity.RESULT_OK){
                editedChatPicture = data.getData().getPath();

                if (chatViewAdapter != null){
                    chatViewAdapter.updatePicture(editedChatPicture);
                }

            }else if (data != null){
                AppLogger.error("An error occurred while trying to get Camera data.", (Exception) data.getSerializableExtra(MaterialCamera.ERROR_EXTRA));
                showErroredSnackBar(getString(R.string.camera_capture_error));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode){
            case weMessage.REQUEST_PERMISSION_CAMERA:
                if (isGranted(grantResults)){
                    launchCamera();
                }
                break;
            case weMessage.REQUEST_PERMISSION_READ_STORAGE:
                if (isGranted(grantResults)){
                    chatViewChoosePhotoErrorTextView.setVisibility(View.GONE);
                    loadChoosePhotoItems();
                } else {
                    chatViewChoosePhotoRecyclerView.setVisibility(View.GONE);
                    chatViewChoosePhotoErrorTextView.setText(getString(R.string.no_media_permission));
                    chatViewChoosePhotoErrorTextView.setVisibility(View.VISIBLE);
                }
                break;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(BUNDLE_IS_IN_EDIT_MODE, isInEditMode);
        outState.putBoolean(BUNDLE_IS_CHOOSE_PHOTO_LAYOUT_SHOWN, isChoosePhotoLayoutShown);

        outState.putString(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);
        outState.putString(BUNDLE_EDITED_NAME, editedName);
        outState.putString(BUNDLE_EDITED_PICTURE, editedChatPicture);

        super.onSaveInstanceState(outState);
    }

    @Override
    public void onResume() {
        if (!isServiceRunning(ConnectionService.class)){
            goToLauncherReconnect();
        }

        super.onResume();
    }

    @Override
    public void onDestroy() {
        weMessage.get().getMessageManager().unhookCallbacks(callbackUuid);
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(chatViewBroadcastReceiver);

        if (isBoundToConnectionService){
            unbindService();
        }

        super.onDestroy();
    }

    @Override
    public void onContactCreate(Contact contact) { }

    @Override
    public void onContactUpdate(Contact oldData, Contact newData) { }

    @Override
    public void onContactListRefresh(List<Contact> contacts) { }

    @Override
    public void onChatAdd(Chat chat) { }

    @Override
    public void onChatUpdate(Chat oldData, final Chat newData) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (chatViewAdapter != null) {
                            if (newData.getUuid().toString().equals(chatUuid) && newData instanceof GroupChat) {
                                toggleIsInChat(newData.isInChat(), false);
                                chatViewAdapter.loadChat((GroupChat) newData);
                            }
                        }
                    }
                }, 100L);
            }
        });
    }

    @Override
    public void onUnreadMessagesUpdate(Chat chat, boolean hasUnreadMessages) { }

    @Override
    public void onChatRename(final Chat chat, String displayName) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (chatViewAdapter != null){
                            if (chat.getUuid().toString().equals(chatUuid)) {
                                chatViewAdapter.loadChat((GroupChat) chat);
                            }
                        }
                    }
                }, 100L);
            }
        });
    }

    @Override
    public void onParticipantAdd(final Chat chat, Contact contact) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (chatViewAdapter != null){
                            if (chat.getUuid().toString().equals(chatUuid)) {
                                chatViewAdapter.loadChat((GroupChat) chat);
                            }
                        }
                    }
                }, 100L);
            }
        });
    }

    @Override
    public void onParticipantRemove(final Chat chat, Contact contact) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (chatViewAdapter != null){
                            if (chat.getUuid().toString().equals(chatUuid)) {
                                chatViewAdapter.loadChat((GroupChat) chat);
                            }
                        }
                    }
                }, 100L);
            }
        });
    }

    @Override
    public void onLeaveGroup(Chat chat) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toggleIsInChat(false, true);
            }
        });
    }

    @Override
    public void onChatDelete(final Chat chat) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (chat.getUuid().toString().equals(chatUuid)){
                    Intent returnIntent = new Intent(weMessage.get(), ChatListActivity.class);
                    returnIntent.putExtra(weMessage.BUNDLE_CONVERSATION_GO_BACK_REASON, getString(R.string.return_chat_list_chat_deleted));

                    startActivity(returnIntent);
                    getActivity().finish();
                }
            }
        });
    }

    @Override
    public void onChatListRefresh(final List<Chat> chats) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isInEditMode){
                    toggleEditMode(false, false);
                }

                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (chatViewAdapter != null){
                            for (Chat c : chats){
                                if (c.getUuid().toString().equals(chatUuid) && c instanceof GroupChat){
                                    chatViewAdapter.loadChat((GroupChat) c);
                                }
                            }
                        }
                    }
                }, 100L);
            }
        });
    }

    @Override
    public void onMessageAdd(Message message) { }

    @Override
    public void onMessageUpdate(Message oldData, Message newData) { }

    @Override
    public void onMessageDelete(Message message) { }

    @Override
    public void onMessagesQueueFinish(List<MessageBase> messages) { }

    @Override
    public void onMessagesRefresh() { }

    @Override
    public void onActionMessageAdd(ActionMessage message) { }

    @Override
    public void onMessageSendFailure(final JSONMessage jsonMessage, final ReturnType returnType) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showMessageSendFailureSnackbar(jsonMessage, returnType);
            }
        });
    }

    @Override
    public void onActionPerformFailure(final JSONAction jsonAction, final ReturnType returnType) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showActionFailureSnackbar(jsonAction, returnType);
            }
        });
    }

    @Override
    public void onAttachmentSendFailure(final FailReason failReason) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showAttachmentSendFailureSnackbar(failReason);
            }
        });
    }

    @Override
    public void onAttachmentReceiveFailure(final FailReason failReason) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showAttachmentReceiveFailureSnackbar(failReason);
            }
        });
    }

    public void returnToConversationScreen() {
        Intent launcherIntent = new Intent(weMessage.get(), ConversationActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_RETURN_POINT, ChatListActivity.class.getName());
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void toggleEditMode(boolean value, boolean saveChanges){
        if (value){
            isInEditMode = true;
            editedName = ((GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid)).getDisplayName();

            chatViewAdapter.toggleEditMode(true, false);
            toolbarBackButton.setVisibility(View.GONE);
            toolbarCancelButton.setVisibility(View.VISIBLE);
            toolbarEditButton.setText(R.string.word_done);
        }else {
            isInEditMode = false;

            chatViewAdapter.toggleEditMode(false, saveChanges);

            toolbarBackButton.setVisibility(View.VISIBLE);
            toolbarCancelButton.setVisibility(View.GONE);
            toolbarEditButton.setText(R.string.word_edit);

            if (isChoosePhotoLayoutShown){
                toggleChoosePhotoLayout(false);
            }

            try {
                if (saveChanges) {
                    GroupChat oldVal = ((GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid));

                    if (editedName != null && !editedName.equals(oldVal.getDisplayName())) {
                        serviceConnection.getConnectionService().getConnectionHandler().sendOutgoingRenameGroupAction(oldVal, editedName);
                    }

                    if (!StringUtils.isEmpty(editedChatPicture)) {
                        if (editedChatPicture.equals("DELETE")) {
                            if (oldVal.getChatPictureFileLocation() != null && !StringUtils.isEmpty(oldVal.getChatPictureFileLocation().getFileLocation())){
                                oldVal.getChatPictureFileLocation().getFile().delete();
                            }

                            oldVal.setChatPictureFileLocation(null);
                        } else {
                            File srcFile = new File(editedChatPicture);

                            if (srcFile.length() > weMessage.MAX_CHAT_ICON_SIZE){
                                DialogDisplayer.generateAlertDialog(getString(R.string.max_file_chat_size_alert_title), getString(R.string.max_file_chat_size_alert_message, FileUtils.getFileSizeString(weMessage.MAX_CHAT_ICON_SIZE)))
                                        .show(getFragmentManager(), "AttachmentMaxFileSizeAlert");
                            }else {
                                File newFile = new File(weMessage.get().getChatIconsFolder(), chatUuid + srcFile.getName());

                                FileUtils.copy(srcFile, newFile);

                                if (oldVal.getChatPictureFileLocation() != null && oldVal.getChatPictureFileLocation().getFile().exists()) {
                                    oldVal.getChatPictureFileLocation().getFile().delete();
                                }

                                oldVal.setChatPictureFileLocation(new FileLocationContainer(newFile));
                            }
                        }
                    }
                    weMessage.get().getMessageManager().updateChat(chatUuid, oldVal, true);
                }
            }catch (Exception ex){
                showErroredSnackBar(getString(R.string.chat_update_error));
                AppLogger.error("An error occurred while updating a group chat", ex);
            }

            editedChatPicture = null;
        }
    }

    private void showChatPictureEditSheet(){
        bottomSheetLayout.showWithSheetView(LayoutInflater.from(getContext()).inflate(R.layout.sheet_chat_view_edit_picture, bottomSheetLayout, false));

        bottomSheetLayout.findViewById(R.id.chatViewEditPictureTake).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bottomSheetLayout.dismissSheet();

                if (isInEditMode) {
                    launchCamera();
                }
            }
        });

        bottomSheetLayout.findViewById(R.id.chatViewEditPictureChoose).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bottomSheetLayout.dismissSheet();

                if (isInEditMode) {
                    toggleChoosePhotoLayout(true);
                }
            }
        });

        bottomSheetLayout.findViewById(R.id.chatViewEditPictureDelete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isInEditMode) {
                    deleteChatPicture();
                }
                bottomSheetLayout.dismissSheet();
            }
        });

        bottomSheetLayout.findViewById(R.id.chatViewEditPictureCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                bottomSheetLayout.dismissSheet();
            }
        });
    }

    private void toggleChoosePhotoLayout(boolean value){
        if (isChoosePhotoLayoutShown != value){
            if (value){
                isChoosePhotoLayoutShown = true;

                chatViewChoosePhotoLayout.animate().alpha(1.0f).translationY(0).setDuration(250).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        super.onAnimationStart(animation);
                        chatViewChoosePhotoLayout.setVisibility(View.VISIBLE);
                    }
                });

                if (hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE, getString(R.string.no_media_permission), "MediaReadPermissionAlertFragment", weMessage.REQUEST_PERMISSION_READ_STORAGE)){
                    loadChoosePhotoItems();
                }
            }else {
                isChoosePhotoLayoutShown = false;

                int height = chatViewChoosePhotoLayout.getHeight();

                chatViewChoosePhotoLayout.animate().alpha(0.f).translationY(height).setDuration(250).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        chatViewChoosePhotoLayout.setVisibility(View.GONE);
                    }
                });
            }
        }
    }

    private void toggleIsInChat(boolean value, boolean reloadLayout){
        if (value){
            if (toolbarEditButton.getVisibility() != View.VISIBLE) {
                toolbarEditButton.setVisibility(View.VISIBLE);
            }
        }else {
            if (isInEditMode){
                toggleEditMode(false, false);
            }
            if (toolbarEditButton.getVisibility() != View.GONE) {
                toolbarEditButton.setVisibility(View.GONE);
            }
        }

        if (reloadLayout){
            chatViewAdapter.loadChat((GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid));
        }
    }

    private void deleteChatPicture() {
        editedChatPicture = "DELETE";
        chatViewAdapter.updatePicture("DELETE");
    }

    private void launchCamera(){
        if (hasPermission(Manifest.permission.CAMERA, getString(R.string.no_camera_permission), "CameraPermissionAlertFragment", weMessage.REQUEST_PERMISSION_CAMERA)) {
            new MaterialCamera(this)
                    .allowRetry(true)
                    .autoSubmit(false)
                    .saveDir(weMessage.get().getAttachmentFolder())
                    .showPortraitWarning(true)
                    .defaultToFrontFacing(false)
                    .retryExits(false)
                    .labelRetry(R.string.word_redo)
                    .labelConfirm(R.string.ok_button)
                    .stillShot()
                    .start(weMessage.REQUEST_CODE_CAMERA);
        }
    }

    private void launchContactView(String contactUuid){
        Intent launcherIntent = new Intent(weMessage.get(), ContactViewActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_CONTACT_VIEW_UUID, contactUuid);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchAddParticipantActivity(){
        Intent launcherIntent = new Intent(weMessage.get(), ChatAddContactActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchFullScreenImageActivity(String imageUri){
        Intent launcherIntent = new Intent(weMessage.get(), MessageImageActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_FULL_SCREEN_IMAGE_URI, imageUri);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void launchFullScreenVideoActivity(String imageUri){
        Intent launcherIntent = new Intent(weMessage.get(), MessageVideoActivity.class);

        launcherIntent.putExtra(weMessage.BUNDLE_FULL_SCREEN_VIDEO_URI, imageUri);
        launcherIntent.putExtra(weMessage.BUNDLE_CONVERSATION_CHAT, chatUuid);

        startActivity(launcherIntent);
        getActivity().finish();
    }

    private void loadChoosePhotoItems(){
        new AsyncTask<Void, Void, ArrayList<String>>(){

            @Override
            protected ArrayList<String> doInBackground(Void... params) {
                ArrayList<String> allUris = new ArrayList<>();

                try {
                    allUris.addAll(getAllImages());
                }catch (Exception ex){
                    showErroredSnackBar(getString(R.string.media_fetch_error));
                    AppLogger.error("An error occurred while fetching media from the device.", ex);
                }

                return allUris;
            }

            @Override
            protected void onPostExecute(ArrayList<String> strings) {
                if (getContext() instanceof Activity && ((Activity) getContext()).isDestroyed()) return;

                onLoadChoosePhotoItems(strings);

                if (choosePhotoAdapter.getItemCount() == 0){
                    chatViewChoosePhotoRecyclerView.setVisibility(View.GONE);
                    chatViewChoosePhotoErrorTextView.setText(getString(R.string.no_media_found));
                    chatViewChoosePhotoErrorTextView.setVisibility(View.VISIBLE);
                }else {
                    chatViewChoosePhotoRecyclerView.setVisibility(View.VISIBLE);
                    chatViewChoosePhotoErrorTextView.setVisibility(View.GONE);
                }
            }
        }.execute();
    }

    private void onLoadChoosePhotoItems(final List<String> filePaths){
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                choosePhotoAdapter = new ChoosePhotoAdapter(filePaths);
                chatViewChoosePhotoRecyclerView.setAdapter(choosePhotoAdapter);
            }
        });
    }

    private ArrayList<String> getAllImages(){
        ArrayList<String> images = new ArrayList<>();
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = { MediaStore.Images.Media.DATA, MediaStore.Images.ImageColumns.DATE_TAKEN, MediaStore.Images.ImageColumns.MIME_TYPE };
        String orderBy = MediaStore.Images.ImageColumns.DATE_TAKEN + " DESC";

        Cursor cursor = getActivity().getContentResolver().query(uri, projection, null, null, orderBy);
        int columnIndexData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
        int mimeIndexData = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);

        if (cursor.getCount() > 0) {
            while (cursor.moveToNext()) {
                if (MimeType.getTypeFromString(cursor.getString(mimeIndexData)) == MimeType.IMAGE) {
                    String imagePath = cursor.getString(columnIndexData);
                    images.add(imagePath);
                }
            }
        }
        cursor.close();

        return images;
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

    private void goToLauncher(){
        if (isAdded() || (getActivity() != null && !getActivity().isFinishing())) {
            Intent launcherIntent = new Intent(weMessage.get(), LaunchActivity.class);

            launcherIntent.putExtra(weMessage.BUNDLE_LAUNCHER_DO_NOT_TRY_RECONNECT, true);

            startActivity(launcherIntent);
            getActivity().finish();
        }
    }

    private void goToLauncherReconnect(){
        if (isAdded() || (getActivity() != null && !getActivity().isFinishing())) {
            Intent launcherIntent = new Intent(weMessage.get(), LaunchActivity.class);

            startActivity(launcherIntent);
            getActivity().finish();
        }
    }

    private boolean hasPermission(final String permission, String rationaleString, String alertTagId, final int requestCode){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && ContextCompat.checkSelfPermission(getActivity(), permission) != PackageManager.PERMISSION_GRANTED) {
            if (shouldShowRequestPermissionRationale(permission)){
                DialogDisplayer.AlertDialogFragment alertDialogFragment = DialogDisplayer.generateAlertDialog(getString(R.string.permissions_error_title), rationaleString);

                alertDialogFragment.setOnDismiss(new Runnable() {
                    @Override
                    public void run() {
                        requestPermissions(new String[] { permission }, requestCode);
                    }
                });
                alertDialogFragment.show(getFragmentManager(), alertTagId);
                return false;
            } else {
                requestPermissions(new String[] { permission }, requestCode);
                return false;
            }
        }
        return true;
    }

    private boolean isGranted(int[] grantResults){
        return (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
    }

    private void showErroredSnackBar(String message){
        if (getView() != null) {
            final Snackbar snackbar = Snackbar.make(getView(), message, ERROR_SNACKBAR_DURATION * 1000);

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

            snackbar.show();
        }
    }

    private void closeKeyboard(){
        InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (getActivity().getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getActivity().getCurrentFocus().getWindowToken(), 0);
        }
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

    private void showDisconnectReasonDialog(Intent bundledIntent, String defaultMessage, Runnable runnable){
        DialogDisplayer.showDisconnectReasonDialog(getContext(), getFragmentManager(), bundledIntent, defaultMessage, runnable);
    }

    private class ChatViewAdapter extends RecyclerView.Adapter {

        public Integer showingDeletePosition;
        private GroupChat groupChat;

        public ArrayList<String> attachmentUris = new ArrayList<>();
        private ArrayList<Contact> contacts = new ArrayList<>();

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            switch (viewType){
                case TYPE_HEADER:
                    return new ChatViewHeaderHolder(LayoutInflater.from(getActivity()), parent);
                case TYPE_CONTACT:
                    return new ContactViewHolder(LayoutInflater.from(getActivity()), parent);
                case TYPE_CONTACT_ATTACHMENT:
                    return new ContactAttachmentViewHolder(LayoutInflater.from(getActivity()), parent);
                case TYPE_ATTACHMENT:
                    return new AttachmentHolder(LayoutInflater.from(getActivity()), parent);
                default:
                    return null;
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()){

                case TYPE_HEADER:
                    ((ChatViewHeaderHolder) holder).bind(groupChat);
                    break;
                case TYPE_CONTACT:
                    ((ContactViewHolder) holder).bind(groupChat, contacts.get(position - 1));
                    break;
                case TYPE_CONTACT_ATTACHMENT:
                    ((ContactAttachmentViewHolder) holder).bind(groupChat);
                    break;
                case TYPE_ATTACHMENT:
                    ((AttachmentHolder) holder).bind(attachmentUris.get(position - contacts.size() - 2));
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) return TYPE_HEADER;
            if (position == contacts.size() + 1) return TYPE_CONTACT_ATTACHMENT;

            boolean contactRange = (position > 0 && position <= contacts.size());

            if (contactRange) return TYPE_CONTACT;

            return TYPE_ATTACHMENT;
        }

        @Override
        public int getItemCount() {
            return contacts.size() + attachmentUris.size() + 2;
        }

        public void toggleEditMode(boolean value, boolean saveChanges){
            if (getHeaderHolder() != null) {
                getHeaderHolder().toggleEditMode(groupChat, value, saveChanges);
            }
            notifyItemChanged(0);
            chatViewRecyclerView.scrollBy(0, 0);
        }

        public void updatePicture(String path){
            if (getHeaderHolder() != null){
                getHeaderHolder().updatePicture(path);
            }
        }

        public void loadChat(GroupChat groupChat){
            this.groupChat = groupChat;
            contacts.clear();
            attachmentUris.clear();

            showingDeletePosition = null;

            for (Contact c : groupChat.getParticipants()){
                if (!c.isBlocked()) {
                    contacts.add(c);
                }
            }

            loadAttachmentItems();
        }

        private ChatViewHeaderHolder getHeaderHolder(){
            try {
                RecyclerView.ViewHolder viewHolder = chatViewRecyclerView.getChildViewHolder(chatViewRecyclerView.getChildAt(0));

                if (viewHolder instanceof ChatViewHeaderHolder) {
                    return ((ChatViewHeaderHolder) viewHolder);
                }
            }catch (Exception ex){ }
            return null;
        }

        private void loadAttachmentItems(){
            new AsyncTask<Void, Void, ArrayList<String>>() {

                @Override
                protected ArrayList<String> doInBackground(Void... params) {
                    ArrayList<String> allUris = new ArrayList<>();

                    try {
                        for (Attachment a : weMessage.get().getMessageDatabase().getReversedAttachmentsInChat(groupChat.getUuid().toString(), 0, Integer.MAX_VALUE)) {
                            String fileLoc = a.getFileLocation().getFileLocation();

                            if (!StringUtils.isEmpty(fileLoc) && !allUris.contains(fileLoc)) {
                                MimeType mimeType = AndroidUtils.getMimeTypeFromPath(fileLoc);

                                if (mimeType == MimeType.IMAGE || mimeType == MimeType.VIDEO) {
                                    allUris.add(fileLoc);
                                }
                            }
                        }
                    } catch (Exception ex) {
                        showErroredSnackBar(getString(R.string.media_fetch_error));
                        AppLogger.error("An error occurred while fetching media from the device.", ex);
                    }

                    return allUris;
                }

                @Override
                protected void onPostExecute(ArrayList<String> strings) {
                    if (getContext() instanceof Activity && ((Activity) getContext()).isDestroyed()) return;

                    attachmentUris = strings;
                    notifyDataSetChanged();
                    chatViewRecyclerView.scrollBy(0, 0);
                }
            }.execute();
        }
    }

    private class ChatViewHeaderHolder extends RecyclerView.ViewHolder {

        private boolean isInit = false;

        private LinearLayout chatViewPictureContainer;
        private ImageView chatViewPicture;
        private TextView chatViewEditPictureTextView;
        private ViewSwitcher chatViewNameSwitcher;
        private TextView chatViewName;
        private EditText chatViewEditName;
        private Switch chatDoNotDisturbSwitch;
        private Button chatLeaveButton;
        private TextView chatViewContactsTextView;

        public ChatViewHeaderHolder(LayoutInflater inflater, ViewGroup parent){
            super(inflater.inflate(R.layout.list_item_chat_view_header, parent, false));
        }

        public void bind(GroupChat chat){
            init(chat);

            Glide.with(ChatViewFragment.this).load(IOUtils.getChatIconUri(chat, IOUtils.IconSize.LARGE)).into(chatViewPicture);
            chatViewName.setText(chat.getUIDisplayName(false));
            chatViewContactsTextView.setText(getString(R.string.participants, chat.getParticipants().size()));

            if (StringUtils.isEmpty(editedChatPicture)) {
                Glide.with(ChatViewFragment.this).load(IOUtils.getChatIconUri(chat, IOUtils.IconSize.LARGE)).into(chatViewPicture);
            }else if (editedChatPicture.equals("DELETE")) {
                Glide.with(ChatViewFragment.this).load(IOUtils.getDefaultChatUri(IOUtils.IconSize.LARGE)).into(chatViewPicture);
            }else {
                Glide.with(ChatViewFragment.this).load(editedChatPicture).into(chatViewPicture);
            }

            toggleEditMode(chat, isInEditMode, false);

            if (chat.isInChat()){
                itemView.findViewById(R.id.chatViewDividerOne).setVisibility(View.VISIBLE);
                chatLeaveButton.setVisibility(View.VISIBLE);
            }else {
                itemView.findViewById(R.id.chatViewDividerOne).setVisibility(View.GONE);
                chatLeaveButton.setVisibility(View.GONE);
            }
        }

        public void toggleEditMode(GroupChat chat, boolean value, boolean saveChanges){
            if (value) {
                if (chatViewNameSwitcher.getNextView().getId() == R.id.chatViewEditName) {
                    chatViewNameSwitcher.showNext();
                }
                chatViewEditName.setText(editedName);
                chatViewEditPictureTextView.setVisibility(View.VISIBLE);
                chatViewEditPictureTextView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (!isChoosePhotoLayoutShown) {
                            showChatPictureEditSheet();
                        }
                    }
                });
            }

            if (!value) {
                if (saveChanges){
                    editedName = chatViewEditName.getText().toString();
                    clearEditText(chatViewEditName, true);
                }else {
                    Glide.with(ChatViewFragment.this).load(IOUtils.getChatIconUri(chat, IOUtils.IconSize.LARGE)).into(chatViewPicture);
                }

                if (chatViewNameSwitcher.getNextView().getId() == R.id.chatViewName) {
                    chatViewNameSwitcher.showNext();
                }

                closeKeyboard();
                clearEditText(chatViewEditName, false);

                chatViewEditPictureTextView.setVisibility(View.GONE);
                chatViewPictureContainer.setOnClickListener(null);
                chatViewPictureContainer.setClickable(false);
            }
        }

        public void updatePicture(String path){
            if (path.equals("DELETE")) {
                Glide.with(ChatViewFragment.this).load(IOUtils.getDefaultChatUri(IOUtils.IconSize.LARGE)).into(chatViewPicture);
            }else {
                Glide.with(ChatViewFragment.this).load(path).into(chatViewPicture);
            }
        }

        private void init(final GroupChat chat){
            if (!isInit){
                isInit = true;

                chatViewPictureContainer = itemView.findViewById(R.id.chatViewPictureContainer);
                chatViewPicture = itemView.findViewById(R.id.chatViewPicture);
                chatViewEditPictureTextView = itemView.findViewById(R.id.chatViewEditPictureTextView);
                chatViewNameSwitcher = itemView.findViewById(R.id.chatViewNameSwitcher);
                chatViewName = itemView.findViewById(R.id.chatViewName);
                chatViewEditName = itemView.findViewById(R.id.chatViewEditName);
                chatDoNotDisturbSwitch = itemView.findViewById(R.id.chatViewDoNotDisturbSwitch);
                chatLeaveButton = itemView.findViewById(R.id.chatViewLeaveButton);
                chatViewContactsTextView = itemView.findViewById(R.id.chatViewContactsTextView);

                chatViewEditName.setOnKeyListener(new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View view, int keyCode, KeyEvent event) {
                        if ((event.getAction() == KeyEvent.ACTION_DOWN) && (keyCode == KeyEvent.KEYCODE_ENTER)) {
                            editedName = chatViewEditName.getText().toString();
                            clearEditText(chatViewEditName, true);
                            return true;
                        }
                        return false;
                    }
                });

                chatDoNotDisturbSwitch.setChecked(chat.isDoNotDisturb());
                chatDoNotDisturbSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                        GroupChat newChat = chat;

                        weMessage.get().getMessageManager().updateChat(newChat.getUuid().toString(), newChat.setDoNotDisturb(b), true);
                    }
                });

                chatLeaveButton.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (chat.getParticipants().size() > 2) {
                            serviceConnection.getConnectionService().getConnectionHandler().sendOutgoingLeaveGroupAction(chat);
                        }else {
                            showErroredSnackBar(getString(R.string.action_failure_leave_chat_group_size));
                        }
                    }
                });
            }
        }
    }

    private class ContactViewHolder extends RecyclerView.ViewHolder {

        private boolean isInit = false;

        private SwipeLayout swipeLayout;
        private LinearLayout chatContactRemoveButtonLayout;
        private ImageView chatContactPictureView;
        private TextView chatContactDisplayNameView;

        public ContactViewHolder(LayoutInflater inflater, ViewGroup parent){
            super(inflater.inflate(R.layout.list_item_chat_view_contact, parent, false));
        }

        public void bind(GroupChat chat, Contact contact){
            init();

            final String contactUuid = contact.getUuid().toString();
            final String contactHandle = contact.getHandle().getHandleID();

            itemView.findViewById(R.id.chatContactLayout).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    launchContactView(contactUuid);
                }
            });

            chatContactRemoveButtonLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    GroupChat groupChat = (GroupChat) weMessage.get().getMessageDatabase().getChatByUuid(chatUuid);

                    if (groupChat.getParticipants().size() > 2) {
                        serviceConnection.getConnectionService().getConnectionHandler().sendOutgoingRemoveParticipantAction(groupChat, contactHandle);
                    }else {
                        showErroredSnackBar(getString(R.string.action_failure_remove_participant_group_size));
                        closeUnderlyingView();
                    }
                }
            });

            chatContactDisplayNameView.setText(contact.getUIDisplayName());
            Glide.with(ChatViewFragment.this).load(IOUtils.getContactIconUri(contact, IOUtils.IconSize.NORMAL)).into(chatContactPictureView);

            swipeLayout.setSwipeEnabled(chat.isInChat());

            swipeLayout.addDrag(SwipeLayout.DragEdge.Right, itemView.findViewById(R.id.chatContactRemoveButtonLayout));
            swipeLayout.addSwipeListener(new SwipeLayout.SwipeListener() {
                @Override
                public void onStartOpen(SwipeLayout layout) {
                    if (chatViewAdapter.showingDeletePosition != null && chatViewAdapter.showingDeletePosition != getAdapterPosition()){
                        RecyclerView.ViewHolder viewHolder = chatViewRecyclerView.findViewHolderForAdapterPosition(chatViewAdapter.showingDeletePosition);

                        if (viewHolder != null && viewHolder instanceof ContactViewHolder){
                            ((ContactViewHolder) viewHolder).closeUnderlyingView();
                        }
                    }
                }

                @Override
                public void onOpen(SwipeLayout layout) {
                    chatViewAdapter.showingDeletePosition = getAdapterPosition();
                }

                @Override
                public void onStartClose(SwipeLayout layout) {

                }

                @Override
                public void onClose(SwipeLayout layout) {
                    if (chatViewAdapter.showingDeletePosition != null && chatViewAdapter.showingDeletePosition == getAdapterPosition()) {
                        chatViewAdapter.showingDeletePosition = null;
                    }
                }

                @Override
                public void onUpdate(SwipeLayout layout, int leftOffset, int topOffset) {

                }

                @Override
                public void onHandRelease(SwipeLayout layout, float xvel, float yvel) {

                }
            });
        }

        public void closeUnderlyingView(){
            if (swipeLayout.getOpenStatus() != SwipeLayout.Status.Close) {
                swipeLayout.close();
            }
        }

        private void init(){
            if (!isInit) {
                isInit = true;

                swipeLayout = (SwipeLayout) itemView;
                chatContactRemoveButtonLayout = itemView.findViewById(R.id.chatContactRemoveButtonLayout);
                chatContactPictureView = itemView.findViewById(R.id.chatContactPictureView);
                chatContactDisplayNameView = itemView.findViewById(R.id.chatContactDisplayNameView);
            }
        }
    }

    private class ContactAttachmentViewHolder extends RecyclerView.ViewHolder {

        public ContactAttachmentViewHolder(LayoutInflater inflater, ViewGroup parent){
            super(inflater.inflate(R.layout.list_item_chat_view_contact_attachments, parent, false));

            itemView.findViewById(R.id.chatViewAddParticipant).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    launchAddParticipantActivity();
                }
            });
        }

        public void bind(GroupChat chat){
            if (chatViewAdapter.attachmentUris.size() > 0){
                itemView.findViewById(R.id.mediaErrorTextView).setVisibility(View.GONE);
            }else {
                itemView.findViewById(R.id.mediaErrorTextView).setVisibility(View.VISIBLE);
            }

            if (chat.isInChat()){
                itemView.findViewById(R.id.chatViewAddParticipant).setVisibility(View.VISIBLE);
            }else {
                itemView.findViewById(R.id.chatViewAddParticipant).setVisibility(View.GONE);
            }
        }
    }

    private class AttachmentHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private String path;
        private RelativeLayout galleryViewLayout;
        private ImageView galleryImageView;
        private ImageView videoIndicatorView;

        public AttachmentHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.list_item_full_gallery_view, parent, false));

            galleryViewLayout = itemView.findViewById(R.id.galleryViewLayout);
            galleryImageView = itemView.findViewById(R.id.galleryImageView);
            videoIndicatorView = itemView.findViewById(R.id.videoIndicatorView);

            itemView.setOnClickListener(this);
        }

        public void bind(String path){
            this.path = path;

            ViewGroup.LayoutParams layoutParams = galleryViewLayout.getLayoutParams();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

            layoutParams.width = displayMetrics.widthPixels / 2;
            layoutParams.height = displayMetrics.widthPixels / 2;

            galleryViewLayout.setLayoutParams(layoutParams);
            videoIndicatorView.setVisibility(View.INVISIBLE);

            MimeType mimeType = AndroidUtils.getMimeTypeFromPath(path);

            if (mimeType == MimeType.IMAGE) {
                Glide.with(itemView.getContext()).load(path).transition(DrawableTransitionOptions.withCrossFade()).into(galleryImageView);
            }else if (mimeType == MimeType.VIDEO){
                itemView.setAlpha(0.0f);

                new AsyncTask<String, Void, Bitmap>(){
                    @Override
                    protected Bitmap doInBackground(String... params) {
                        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

                        retriever.setDataSource(params[0]);
                        Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(params[0], MediaStore.Video.Thumbnails.FULL_SCREEN_KIND);
                        retriever.release();

                        return bitmap;
                    }

                    @Override
                    protected void onPostExecute(Bitmap bitmap) {
                        if (getContext() instanceof Activity && ((Activity) getContext()).isDestroyed()) return;

                        galleryImageView.setImageBitmap(bitmap);
                        videoIndicatorView.setVisibility(View.VISIBLE);
                        itemView.animate().alpha(1.0f).setDuration(250);
                    }
                }.execute(path);
            }
        }

        @Override
        public void onClick(View v) {
            MimeType mimeType = AndroidUtils.getMimeTypeFromPath(path);

            if (mimeType == MimeType.IMAGE){
                launchFullScreenImageActivity(path);
            }else if (mimeType == MimeType.VIDEO){
                launchFullScreenVideoActivity(path);
            }
        }
    }

    private class ChoosePhotoAdapter extends RecyclerView.Adapter<ChoosePhotoHolder> {

        private List<String> filePaths = new ArrayList<>();

        public ChoosePhotoAdapter(List<String> filePaths){
            this.filePaths = filePaths;
        }

        @Override
        public ChoosePhotoHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater layoutInflater = LayoutInflater.from(getActivity());

            return new ChoosePhotoHolder(layoutInflater, parent);
        }

        @Override
        public void onBindViewHolder(ChoosePhotoHolder holder, int position) {
            String path = filePaths.get(position);
            int size = chatViewRecyclerView.getWidth() / 2;

            holder.bind(path, size);
        }

        @Override
        public int getItemCount() {
            return filePaths.size();
        }
    }

    private class ChoosePhotoHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private String path;
        private RelativeLayout galleryViewLayout;
        private ImageView galleryImageView;

        public ChoosePhotoHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.list_item_full_gallery_view, parent, false));

            galleryViewLayout = itemView.findViewById(R.id.galleryViewLayout);
            galleryImageView = itemView.findViewById(R.id.galleryImageView);

            itemView.setOnClickListener(this);
        }

        public void bind(String path, int imageSize){
            this.path = path;

            ViewGroup.LayoutParams layoutParams = galleryViewLayout.getLayoutParams();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

            layoutParams.width = imageSize;
            layoutParams.height = imageSize;

            galleryViewLayout.setLayoutParams(layoutParams);

            Glide.with(itemView.getContext()).load(path).transition(DrawableTransitionOptions.withCrossFade()).into(galleryImageView);

        }

        @Override
        public void onClick(View v) {
            if (chatViewAdapter != null){
                editedChatPicture = path;
                chatViewAdapter.updatePicture(editedChatPicture);
                toggleChoosePhotoLayout(false);
            }
        }
    }
}