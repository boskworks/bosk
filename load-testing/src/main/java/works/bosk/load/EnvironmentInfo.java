package works.bosk.load;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Records the machine and JVM that a run happened on.
 *
 * <p>Load results are only comparable across runs if we know what they ran on. On macOS the
 * extra {@code sysctl} values distinguish performance and efficiency cores, which matter when
 * choosing thread counts.
 */
public record EnvironmentInfo(
	int availableProcessors,
	Integer performanceCores,
	Integer efficiencyCores,
	long maxMemoryBytes,
	String osName,
	String osArch,
	String javaVersion,
	String javaVendor,
	String vmName,
	String cpuModel
) {

	public static EnvironmentInfo capture() {
		return new EnvironmentInfo(
			Runtime.getRuntime().availableProcessors(),
			sysctlInt("hw.perflevel0.logicalcpu"),
			sysctlInt("hw.perflevel1.logicalcpu"),
			Runtime.getRuntime().maxMemory(),
			System.getProperty("os.name"),
			System.getProperty("os.arch"),
			System.getProperty("java.version"),
			System.getProperty("java.vendor"),
			System.getProperty("java.vm.name"),
			detectCpuModel()
		);
	}

	private static String detectCpuModel() {
		String brand = sysctl("machdep.cpu.brand_string");
		return brand != null ? brand : sysctl("hw.model");
	}

	private static Integer sysctlInt(String key) {
		String value = sysctl(key);
		if (value == null) {
			return null;
		} else {
			try {
				return Integer.valueOf(value);
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	private static String sysctl(String key) {
		try {
			Process process = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
			String line;
			try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
				line = reader.readLine();
			}
			if (process.waitFor() == 0 && line != null && !line.isBlank()) {
				return line.trim();
			} else {
				return null;
			}
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}
}
