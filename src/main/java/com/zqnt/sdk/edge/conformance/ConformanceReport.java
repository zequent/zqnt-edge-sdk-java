package com.zqnt.sdk.edge.conformance;

import java.util.List;

/** What {@link EdgeAdapterConformance} found; conformant when there are no problems. */
public record ConformanceReport(List<String> problems) {

	public ConformanceReport {
		problems = List.copyOf(problems);
	}

	public boolean passed() {
		return problems.isEmpty();
	}

	/** For JUnit and friends: throws an {@link AssertionError} listing every problem. */
	public void assertPassed() {
		if (!passed()) {
			throw new AssertionError("Edge adapter is not conformant:\n - " + String.join("\n - ", problems));
		}
	}
}
