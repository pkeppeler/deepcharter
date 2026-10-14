import java.util.ArrayList;
import java.util.List;

// Run by checkRunJvmOom (gradle/run-jvm.gradle) in a 16 MB JVM. Fills the heap, catches the OutOfMemoryError the way a test
// harness or a server crash handler does, and returns normally. Without -XX:+ExitOnOutOfMemoryError that is a JVM that exits 0.
public final class OomProbe {
	private OomProbe() {
	}

	public static void main(String[] args) {
		List<byte[]> hoard = new ArrayList<>();
		try {
			while (true) {
				hoard.add(new byte[1 << 20]);
			}
		} catch (OutOfMemoryError caught) {
			hoard.clear();
			System.out.println("probe: caught " + caught.getMessage());
		}
	}
}
