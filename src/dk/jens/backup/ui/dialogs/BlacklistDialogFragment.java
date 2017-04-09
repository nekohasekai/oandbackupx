package dk.jens.backup.ui.dialogs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import dk.jens.backup.AppInfo;
import dk.jens.backup.BlacklistListener;
import dk.jens.backup.OAndBackup;
import dk.jens.backup.R;
import dk.jens.backup.schedules.Scheduler;

import java.util.ArrayList;

public class BlacklistDialogFragment extends DialogFragment {
    private ArrayList<BlacklistListener> blacklistListeners = new ArrayList<>();

    public void addBlacklistListener(BlacklistListener listener) {
        blacklistListeners.add(listener);
    }

    @Override
    @NonNull
    public Dialog onCreateDialog(Bundle savedInstance) {
        final ArrayList<String> selections = new ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        Bundle args = getArguments();
        int blacklistId = args.getInt("blacklistId", Scheduler.GLOBALBLACKLISTID);
        ArrayList<AppInfo> appInfoList = OAndBackup.appInfoList;
        ArrayList<String> labels = new ArrayList<>();
        for(AppInfo appInfo : appInfoList)
            labels.add(appInfo.getLabel());
        builder.setTitle(R.string.blacklistDialogTitle)
            .setMultiChoiceItems(labels.toArray(new CharSequence[labels.size()]),
                    null, (dialogInterface, which, isChecked) -> {
                String packageName = appInfoList.get(which).getPackageName();
                if (isChecked)
                    selections.add(packageName);
                else if(selections.contains(packageName))
                    selections.remove(packageName);
            })
            .setPositiveButton(R.string.dialogOK, (dialogInterface, id) -> {
                for(BlacklistListener listener : blacklistListeners) {
                    listener.onBlacklistChanged(
                        selections.toArray(new CharSequence[selections.size()]),
                        blacklistId);
                }
            });
        return builder.create();
    }
}