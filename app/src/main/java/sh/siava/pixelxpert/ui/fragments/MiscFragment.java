package sh.siava.pixelxpert.ui.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.widget.Toast;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.IRootProviderService;
import sh.siava.pixelxpert.PixelXpert;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.utils.ControlledPreferenceFragmentCompat;
import sh.siava.pixelxpert.utils.NTPTimeSyncer;
import sh.siava.pixelxpert.utils.TimeSyncScheduler;

public class MiscFragment extends ControlledPreferenceFragmentCompat {
	@Override
	public String getTitle() {
		return getString(R.string.misc_header);
	}

	@Override
	public int getLayoutResource() {
		return R.xml.misc_prefs;
	}

	@Override
	public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
		super.onCreatePreferences(savedInstanceState, rootKey);

		findPreference("SyncNTPTimeNow")
				.setOnPreferenceClickListener(preference -> {
					syncNTP();

					return true;
				});

		findPreference("ExportDiagnosticsReport")
				.setOnPreferenceClickListener(preference -> {
					exportDiagnosticsReport();

					return true;
				});
	}

	@Override
	public void updateScreen(String key) {
		super.updateScreen(key);

		if (key == null) return;

		switch (key) {
			case "SyncNTPTime":
			case "TimeSyncInterval":
				TimeSyncScheduler.scheduleTimeSync(getContext());
				break;
		}
	}

	private void syncNTP() {
		boolean successful = new NTPTimeSyncer(getContext()).syncTimeNow();

		int toastResource = successful
				? R.string.sync_ntp_successful
				: R.string.sync_ntp_failed;

		Toast.makeText(getContext(), toastResource, Toast.LENGTH_SHORT).show();
	}

	private void exportDiagnosticsReport() {
		Toast.makeText(getContext(), R.string.diagnostics_report_generating, Toast.LENGTH_SHORT).show();

		new Thread(() -> {
			String report = buildDiagnosticsReport();
			if (getActivity() == null) {
				return;
			}

			requireActivity().runOnUiThread(() -> shareDiagnosticsReport(report));
		}).start();
	}

	private String buildDiagnosticsReport() {
		IRootProviderService rootService = PixelXpert.get().getRootService();
		if (rootService == null) {
			PixelXpert.get().tryConnectRootService();
			try {
				Thread.sleep(1500);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			rootService = PixelXpert.get().getRootService();
		}

		if (rootService == null) {
			return "PixelXpert Diagnostics\n"
					+ "Application ID: " + BuildConfig.APPLICATION_ID + "\n"
					+ "Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
					+ "Root service: unavailable\n";
		}

		try {
			return rootService.buildDiagnosticsReport();
		} catch (RemoteException e) {
			return "PixelXpert Diagnostics\n"
					+ "Application ID: " + BuildConfig.APPLICATION_ID + "\n"
					+ "Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
					+ "Root service error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n";
		}
	}

	private void shareDiagnosticsReport(String report) {
		Intent sendIntent = new Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_report_title))
				.putExtra(Intent.EXTRA_TEXT, report);

		startActivity(Intent.createChooser(sendIntent, getString(R.string.diagnostics_report_share_title)));
	}

}
