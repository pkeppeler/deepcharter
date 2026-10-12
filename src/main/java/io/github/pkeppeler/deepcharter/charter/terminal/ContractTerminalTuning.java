package io.github.pkeppeler.deepcharter.charter.terminal;

/**
 * Tunables for the contract terminal, read as {@code ContractTerminalTuning.DEFAULT.thing()}.
 *
 * @param listedRows the most charters, or applications, the screen lists at once. The server sends no more than this.
 * @param revivableRows the most dormant charters the screen offers to revive at once, under the found button. The server sends no more than this.
 */
public record ContractTerminalTuning(int listedRows, int revivableRows) {
	public static final ContractTerminalTuning DEFAULT = new ContractTerminalTuning(5, 3);

	public ContractTerminalTuning {
		if (listedRows < 1) {
			throw new IllegalArgumentException("listedRows must be at least 1, got " + listedRows);
		}
		if (revivableRows < 1) {
			throw new IllegalArgumentException("revivableRows must be at least 1, got " + revivableRows);
		}
	}
}
