/**
 * Public, SemVer-stable SPI surface for Vonix Server Utilities 26.1.2.
 *
 * <p>This is the only package treated as a public companion contract. Internal
 * packages ({@code command}, {@code database}, {@code features.panel} adapters,
 * {@code donation_ranks}) may change without notice.
 *
 * <p>VSU 2.0.2 is a standalone public core. Optional companions consume this
 * package only. Website, Venary, HTTP, API-key, and Player Panel types are
 * not part of this contract and must not be compiled against this package.
 *
 * <p>Versioning:
 * <ul>
 *   <li><b>MAJOR</b> — breaking SPI changes.</li>
 *   <li><b>MINOR</b> — additive methods/types with fail-closed defaults.</li>
 *   <li><b>PATCH</b> — fixes and documentation.</li>
 * </ul>
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link network.vonix.serverutilities.api.VonixPanels} — optional bound SPI.
 *       Empty when VSU has not started. Companion presence does not enable features.</li>
 *   <li>{@link network.vonix.serverutilities.api.VonixPanel} — immutable projections,
 *       capability reporting, restricted spawn/named-home teleports, bounded events.</li>
 *   <li>{@link network.vonix.serverutilities.api.InventoryProvider} — existing
 *       {@code /backsee} inventory SPI. Panel inventory copies are read-only.</li>
 * </ul>
 *
 * @since 2.0.2
 */
package network.vonix.serverutilities.api;
