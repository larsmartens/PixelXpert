package sh.siava.pixelxpert.xposed.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TrafficSamplerTest {
	@Test
	public void counterDeltaReturnsDifference() {
		assertEquals(750L, TrafficSampler.counterDelta(1_000L, 250L));
	}

	@Test
	public void counterDeltaRejectsUnsupportedAndRolledBackCounters() {
		assertEquals(0L, TrafficSampler.counterDelta(-1L, 250L));
		assertEquals(0L, TrafficSampler.counterDelta(100L, 250L));
	}

	@Test
	public void bytesPerSecondUsesElapsedTime() {
		assertEquals(2_048L, TrafficSampler.bytesPerSecond(1_024L, 500L));
		assertEquals(0L, TrafficSampler.bytesPerSecond(1_024L, 0L));
	}
}
