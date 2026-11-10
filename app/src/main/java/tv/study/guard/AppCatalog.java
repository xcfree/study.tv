package tv.study.guard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AppCatalog {
    static final class Entry {
        final ComponentName component;
        final String label;
        final ResolveInfo info;

        Entry(ComponentName component, String label, ResolveInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
        }

        String key() {
            return component.getPackageName();
        }

        Drawable icon(PackageManager packages) {
            return info.loadIcon(packages);
        }

        Intent intent() {
            return new Intent(Intent.ACTION_MAIN).setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    static List<Entry> load(Context context) {
        PackageManager packages = context.getPackageManager();
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent launchers = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : packages.queryIntentActivities(launchers, 0)) {
                String packageName = info.activityInfo.packageName;
                if (context.getPackageName().equals(packageName) || entries.containsKey(packageName)) continue;
                ComponentName component = new ComponentName(packageName, info.activityInfo.name);
                entries.put(packageName, new Entry(component, info.loadLabel(packages).toString(), info));
            }
        }
        List<Entry> sorted = new ArrayList<>(entries.values());
        Collator collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        Collections.sort(sorted, (first, second) -> collator.compare(first.label, second.label));
        return sorted;
    }
}
