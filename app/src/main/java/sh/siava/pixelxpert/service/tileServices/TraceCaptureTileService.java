package sh.siava.pixelxpert.service.tileServices;

import static android.service.quicksettings.Tile.STATE_INACTIVE;

import android.os.Handler;
import android.os.ProfilingManager;
import android.os.ProfilingResult;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.Executor;

import sh.siava.pixelxpert.R;

/**
 * Quick Settings tile that captures a system trace on demand using {@link ProfilingManager}
 * (available since API 35). Useful for diagnosing performance issues on device without external
 * tooling; the resulting perfetto trace path is reported via a toast.
 */
public class TraceCaptureTileService extends TileService {

	@Override
	public void onStartListening() {
		super.onStartListening();

		Tile tile = getQsTile();
		if (tile != null) {
			tile.setState(STATE_INACTIVE);
			tile.updateTile();
		}
	}

	@Override
	public void onClick() {
		super.onClick();

		ProfilingManager profilingManager = getSystemService(ProfilingManager.class);
		if (profilingManager == null) {
			toast(getString(R.string.trace_capture_unavailable));
			return;
		}

		toast(getString(R.string.trace_capture_started));

		Executor executor = command -> new Thread(command).start();
		Handler mainHandler = new Handler(getMainLooper());

		try {
			profilingManager.requestProfiling(
					ProfilingManager.PROFILING_TYPE_SYSTEM_TRACE,
					null,
					"PixelXpert",
					null,
					executor,
					(ProfilingResult result) -> {
						String message = result.getErrorCode() == ProfilingResult.ERROR_NONE
								? getString(R.string.trace_capture_done, result.getResultFilePath())
								: getString(R.string.trace_capture_failed, result.getErrorMessage());
						mainHandler.post(() -> toast(message));
					});
		} catch (Throwable t) {
			toast(getString(R.string.trace_capture_failed, String.valueOf(t.getMessage())));
		}
	}

	private void toast(String text) {
		Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
	}
}
