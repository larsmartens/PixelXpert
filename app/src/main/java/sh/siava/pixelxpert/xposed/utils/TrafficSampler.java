package sh.siava.pixelxpert.xposed.utils;

final class TrafficSampler {
	private TrafficSampler() {}

	static long counterDelta(long current, long previous) {
		if (current < 0 || previous < 0 || current < previous) {
			return 0;
		}
		return current - previous;
	}

	static long bytesPerSecond(long bytes, long elapsedMillis) {
		if (bytes <= 0 || elapsedMillis <= 0) {
			return 0;
		}
		double rate = bytes * 1000d / elapsedMillis;
		return rate >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) rate;
	}
}
