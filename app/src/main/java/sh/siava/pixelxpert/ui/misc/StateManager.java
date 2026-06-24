package sh.siava.pixelxpert.ui.misc;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public class StateManager {
	private static final StateManager INSTANCE = new StateManager();

	private final MutableLiveData<Boolean> requiresSystemUIRestart = new MutableLiveData<>(false);
	private final MutableLiveData<Boolean> requiresDeviceRestart = new MutableLiveData<>(false);

	public static StateManager getInstance() {
		return INSTANCE;
	}

	public LiveData<Boolean> getRequiresSystemUIRestart() {
		return requiresSystemUIRestart;
	}

	public void setRequiresSystemUIRestart(boolean value) {
		requiresSystemUIRestart.postValue(value);
	}

	public LiveData<Boolean> getRequiresDeviceRestart() {
		return requiresDeviceRestart;
	}

	public void setRequiresDeviceRestart(boolean value) {
		requiresDeviceRestart.postValue(value);
	}
}
