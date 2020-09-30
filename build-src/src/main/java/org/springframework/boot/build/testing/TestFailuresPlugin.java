/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.build.testing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.gradle.BuildResult;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.invocation.Gradle;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestDescriptor;
import org.gradle.api.tasks.testing.TestListener;
import org.gradle.api.tasks.testing.TestResult;

/**
 * Plugin for recording test failures and reporting them at the end of the build.
 *
 * @author Andy Wilkinson
 */
public class TestFailuresPlugin implements Plugin<Project> {

	@Override
	public void apply(Project project) {
		Object testResults = getOrCreateTestResults(project);
		project.getTasks().withType(Test.class,
				(test) -> test.addTestListener(new FailureRecordingTestListener(testResults, test)));
	}

	private Gradle getRootBuild(Gradle build) {
		if (build.getGradle().getParent() == null) {
			return build.getGradle();
		}
		else {
			return getRootBuild(build.getGradle().getParent());
		}
	}

	private Object getOrCreateTestResults(Project project) {
		Object testResults = getRootBuild(project.getGradle()).getRootProject().getExtensions().findByName("testResults");
		if (testResults == null) {
			TestResultsExtension newTestResults = getRootBuild(project.getGradle()).getRootProject().getExtensions().create("testResults", TestResultsExtension.class);
			getRootBuild(project.getGradle()).buildFinished(newTestResults::buildFinished);
			testResults = newTestResults;
		}
		return testResults;
	}

	private final class FailureRecordingTestListener implements TestListener {

		private final List<TestDescriptor> failures = new ArrayList<>();

		private final Object testResults;

		private final Test test;

		private FailureRecordingTestListener(Object testResults, Test test) {
			this.testResults = testResults;
			this.test = test;
		}

		@Override
		public void afterSuite(TestDescriptor descriptor, TestResult result) {
			if (!this.failures.isEmpty()) {
				this.failures.sort(Comparator.comparing(TestDescriptor::getClassName).
						thenComparing(TestDescriptor::getName));
				try {
					// Need to use reflection, see: https://github.com/gradle/gradle/issues/14697
					this.testResults.getClass().getMethod("addFailures", Test.class, List.class).invoke(
							testResults, this.test, this.failures);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}

		@Override
		public void afterTest(TestDescriptor descriptor, TestResult result) {
			if (result.getFailedTestCount() > 0) {
				this.failures.add(descriptor);
			}
		}

		@Override
		public void beforeSuite(TestDescriptor descriptor) {

		}

		@Override
		public void beforeTest(TestDescriptor descriptor) {

		}

	}

	private static final class TestFailure implements Comparable<TestFailure> {

		private final TestDescriptor descriptor;

		private TestFailure(TestDescriptor descriptor) {
			this.descriptor = descriptor;
		}

		@Override
		public int compareTo(TestFailure other) {
			int comparison = this.descriptor.getClassName().compareTo(other.descriptor.getClassName());
			if (comparison == 0) {
				comparison = this.descriptor.getName().compareTo(other.descriptor.getName());
			}
			return comparison;
		}

	}

	public static class TestResultsExtension {

		private final Map<Test, List<TestDescriptor>> testFailures = new TreeMap<>(
				(one, two) -> one.getPath().compareTo(two.getPath()));

		private final Object monitor = new Object();

		public void addFailures(Test test, List<TestDescriptor> testFailures) {
			synchronized (this.monitor) {
				this.testFailures.put(test, testFailures);
			}
		}

		public void buildFinished(BuildResult result) {
			synchronized (this.monitor) {
				if (this.testFailures.isEmpty()) {
					return;
				}
				System.err.println();
				System.err.println("Found test failures in " + this.testFailures.size() + " test task"
						+ ((this.testFailures.size() == 1) ? ":" : "s:"));
				this.testFailures.forEach((task, failures) -> {
					System.err.println();
					System.err.println(task.getPath());
					failures.forEach((failure) -> System.err.println(
							"    " + failure.getClassName() + " > " + failure.getName()));
				});
			}
		}

	}

}
