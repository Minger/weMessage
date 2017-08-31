package scott.wemessage.app.messages.firebase;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.support.v4.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Date;

import scott.wemessage.R;
import scott.wemessage.app.messages.MessageDatabase;
import scott.wemessage.app.messages.objects.Contact;
import scott.wemessage.app.messages.objects.Handle;
import scott.wemessage.app.messages.objects.chats.Chat;
import scott.wemessage.app.messages.objects.chats.GroupChat;
import scott.wemessage.app.security.CryptoType;
import scott.wemessage.app.security.DecryptionTask;
import scott.wemessage.app.security.KeyTextPair;
import scott.wemessage.app.ui.activities.LaunchActivity;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.json.message.JSONNotification;
import scott.wemessage.commons.utils.StringUtils;

public class NotificationService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        //TODO: trim message
        showNotification(remoteMessage);
    }

    private void showNotification(RemoteMessage remoteMessage){
        if (weMessage.get().performNotification()) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            JSONNotification jsonNotification = new JSONNotification(
                    null,
                    remoteMessage.getData().get("encryptedText"),
                    remoteMessage.getData().get("key"),
                    remoteMessage.getData().get("handleId"),
                    remoteMessage.getData().get("chatId"),
                    remoteMessage.getData().get("chatName")
            );

            MessageDatabase database = weMessage.get().getMessageDatabase();
            DecryptionTask decryptionTask = new DecryptionTask(new KeyTextPair(jsonNotification.getEncryptedText(), jsonNotification.getKey()), CryptoType.AES);
            decryptionTask.runDecryptTask();

            String displayName = null;
            String message = "";
            Bitmap largeIcon = null;

            Chat chat = database.getChatByMacGuid(jsonNotification.getChatId());
            Handle handle = database.getHandleByHandleID(jsonNotification.getHandleId());

            if (!StringUtils.isEmpty(jsonNotification.getChatId())) {
                if (chat != null && chat instanceof GroupChat) {
                    displayName = ((GroupChat) chat).getUIDisplayName(false);
                }
            } else if (!StringUtils.isEmpty(jsonNotification.getChatName())) {
                displayName = jsonNotification.getChatName();
            }

            if (!StringUtils.isEmpty(displayName)) {
                if (handle != null) {
                    message = database.getContactByHandle(handle).getUIDisplayName() + ": ";
                } else {
                    message = jsonNotification.getHandleId() + ": ";
                }
            } else {
                if (handle != null) {
                    displayName = database.getContactByHandle(handle).getUIDisplayName();
                } else {
                    displayName = jsonNotification.getHandleId();
                }
            }

            if (chat != null && chat instanceof GroupChat) {
                if (chat.getChatPictureFileLocation() != null && !StringUtils.isEmpty(chat.getChatPictureFileLocation().getFileLocation())) {
                    largeIcon = BitmapFactory.decodeFile(chat.getChatPictureFileLocation().getFileLocation());
                }
            } else {
                if (handle != null) {
                    Contact c = database.getContactByHandle(handle);
                    if (c.getContactPictureFileLocation() != null && !StringUtils.isEmpty(c.getContactPictureFileLocation().getFileLocation())) {
                        largeIcon = BitmapFactory.decodeFile(c.getContactPictureFileLocation().getFileLocation());
                    }
                }
            }
            message += decryptionTask.getDecryptedText();

            Intent intent = new Intent(this, LaunchActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            if (chat != null){
                intent.putExtra(weMessage.BUNDLE_LAUNCHER_GO_TO_CONVERSATION_UUID, chat.getUuid().toString());
            }

            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                    .setSmallIcon(R.drawable.ic_app_notification_white_small)
                    .setContentTitle(displayName)
                    .setContentText(StringUtils.trimORC(message))
                    .setContentIntent(pendingIntent)
                    .setVibrate(new long[]{1000, 1000})
                    .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                    .setWhen(remoteMessage.getSentTime());

            if (largeIcon != null) {
                builder.setLargeIcon(largeIcon);
            }

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_AUTO_CANCEL;

            int id = (int) ((new Date().getTime() / 1000L) % Integer.MAX_VALUE);
            String tag = weMessage.NOTIFICATION_TAG;

            if (chat != null){
                tag += chat.getUuid().toString();
            }

            notificationManager.notify(tag, id, notification);
        }
    }
}