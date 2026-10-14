package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.ui.ScrollList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Client GameTest for the shared terminal scroll list (#517): its clamping, the wheel, the change callback and when it has a hint. It is pure logic, so no screen opens. */
public class ScrollListClientTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        AtomicInteger changes = new AtomicInteger();
        ScrollList list =
                new ScrollList("screen.deepcharter.repair.scroll", () -> 8, () -> 3, changes::incrementAndGet);
        require(
                list.first() == 0 && list.hint().isPresent(),
                "a list longer than the screen starts at row 0 with a hint");
        list.scrollTo(100);
        require(list.first() == 5, "scrolling past the end stops at the last page, got " + list.first());
        list.scrollTo(-4);
        require(
                list.first() == 0 && changes.get() == 2,
                "scrolling before the start stops at row 0; two moves told the screen, got " + changes.get());
        list.scrollTo(0);
        require(changes.get() == 2, "scrolling nowhere does not lay the screen out again");
        require(list.mouseScrolled(-1) && list.first() == 1, "a wheel turn down moves one row down");
        require(list.mouseScrolled(1) && list.first() == 0, "a wheel turn up moves one row up");
        require(!list.mouseScrolled(0), "a wheel that did not turn is not the list's");
        ScrollList fits = new ScrollList("screen.deepcharter.repair.scroll", () -> 3, () -> 3, () -> {});
        require(fits.hint().isEmpty(), "a list that fits has no hint");
        ScrollList unhinted = new ScrollList(() -> 8, () -> 3, () -> {});
        require(unhinted.hint().isEmpty(), "a list built with no hint key has none");
    }
}
