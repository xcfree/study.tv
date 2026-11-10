package tv.study.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Store store = Store.get(context);
            store.syncBoot(true);
            WebAdminService.startIfConfigured(context, store);
        }
    }
}
