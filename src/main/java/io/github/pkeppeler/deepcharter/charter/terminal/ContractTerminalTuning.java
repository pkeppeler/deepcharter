package io.github.pkeppeler.deepcharter.charter.terminal;

/**
 * Tunables for the contract terminal, read as {@code ContractTerminalTuning.DEFAULT.thing()}.
 *
 * @param listedRows the most charters, or applications, the screen lists at once. The server sends no more than this.
 */
public record ContractTerminalTuning(int listedRows) {
	public static final ContractTerminalTuning DEFAULT = new ContractTerminalTuning(5);

	public ContractTerminalTuning {
		if (listedRows < 1) {
			throw new IllegalArgumentException("listedRows must be at least 1, got " + listedRows);
		}
	}
}
