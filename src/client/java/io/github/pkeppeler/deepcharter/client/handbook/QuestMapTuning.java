package io.github.pkeppeler.deepcharter.client.handbook;

import io.github.pkeppeler.deepcharter.client.theme.UiTheme;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The look of the quest map, the flowchart of a chapter, read as {@code QuestMapTuning.current()} from {@code theme/handbook.json}
 * (ADR 0032), beside {@link HandbookScreenTuning}, which owns the paper, the ink and the colours the map is drawn in. Lengths are GUI
 * pixels. Read it on every draw and never keep it: a resource reload swaps in a new one.
 *
 * @param chapterTabWidth width of a chapter tab standing out of the sheet's right edge
 * @param chapterTabTop space between the sheet's top and the first chapter tab
 * @param chapterTabGap space between two chapter tabs
 * @param stepColumns most steps of a flowchart in one row
 * @param stepMinWidth narrowest a step box is drawn; a narrow sheet has fewer columns instead
 * @param stepGap space between two step boxes in a row
 * @param stepHeight height of a step box
 * @param stepPitch distance from the top of one row of step boxes to the next
 * @param stepTextInset space between a step box's edge and its text
 * @param stepMaxLines most lines of a step's text; the last one ends in dots when it is cut
 * @param mapPad space between the chapter title's rule and the first row of steps
 * @param aheadGap space above the next chapter's title and above the redaction bars
 * @param aheadLabelGap distance from the next chapter's title to its steps
 * @param chapterLinkInset how far in from the last step's right edge the dashed link to the next chapter runs
 * @param arrowHeadSize length of each barb of an arrow head
 * @param dashOn drawn pixels of a dashed line
 * @param dashOff gap pixels of a dashed line
 * @param loopRadius corner radius of the ink loop round the current step
 * @param loopPadLeft space between the current step's left edge and its loop
 * @param loopPadTop space between the current step's top edge and its loop
 * @param loopPadRight space between the current step's right edge and its loop
 * @param loopPadBottom space between the current step's bottom edge and its loop
 * @param redactionBarPercent1 width of the first redaction bar, in percent of a step box
 * @param redactionBarPercent2 width of the second redaction bar, in percent of a step box
 * @param redactionBarPercent3 width of the third redaction bar, in percent of a step box
 * @param classifiedStampDegrees turn of the CLASSIFIED stamp over the redaction bars
 * @param noteWidth widest the margin note under the flowchart wraps
 * @param noteInset space between the flowchart's right edge and the margin note
 * @param cardWidth width of the index card
 * @param cardTop where the card's top sits against the sheet's top; negative is above it
 * @param cardMinY highest the card's top may sit on the screen
 * @param cardRightInset how far in from the sheet's right edge the card's right edge sits
 * @param cardInset space between the card's edge and its text
 * @param cardHeadTop distance from the card's top to its head line, "Step n" and the link to the chapter's page
 * @param cardHeadRule distance from the card's top to the red rule under its head line
 * @param cardTextTop distance from the card's top to the step's instruction
 * @param cardRuleTop distance from the card's top to its first ruled line
 * @param cardRuleBottom space kept clear of ruled lines at the card's bottom
 * @param cardItemPitch distance from one needed item to the next in a row on the card
 * @param cardItemRow height of a row of needed items on the card
 * @param cardItemInset space between the card's left edge and the first needed item
 * @param cardBottomPad space below the last row of needed items
 * @param itemIconSize size of an item icon on the card
 * @param itemCountGap space between an item icon and its count
 * @param itemCountTop distance from the top of an item icon to its count's text
 * @param stampLeft distance from the text column's left edge to the centre of the COMPLETE or COMING SOON stamp
 * @param stampTop distance from the sheet's first line to the centre of that stamp
 * @param zoomMin smallest zoom of a flowchart
 * @param zoomMax largest zoom of a flowchart
 * @param zoomStep zoom factor of one scroll notch
 * @param panMargin how much of the flowchart must stay in view when it is dragged away
 * @param toastWidth width of the step-done popup
 * @param toastHeight height of the step-done popup
 * @param toastMillis how long the step-done popup shows
 * @param toastInset space between the step-done popup's edge and its text
 */
public record QuestMapTuning(
        int chapterTabWidth,
        int chapterTabTop,
        int chapterTabGap,
        int stepColumns,
        int stepMinWidth,
        int stepGap,
        int stepHeight,
        int stepPitch,
        int stepTextInset,
        int stepMaxLines,
        int mapPad,
        int aheadGap,
        int aheadLabelGap,
        int chapterLinkInset,
        int arrowHeadSize,
        int dashOn,
        int dashOff,
        int loopRadius,
        int loopPadLeft,
        int loopPadTop,
        int loopPadRight,
        int loopPadBottom,
        int redactionBarPercent1,
        int redactionBarPercent2,
        int redactionBarPercent3,
        int classifiedStampDegrees,
        int noteWidth,
        int noteInset,
        int cardWidth,
        int cardTop,
        int cardMinY,
        int cardRightInset,
        int cardInset,
        int cardHeadTop,
        int cardHeadRule,
        int cardTextTop,
        int cardRuleTop,
        int cardRuleBottom,
        int cardItemPitch,
        int cardItemRow,
        int cardItemInset,
        int cardBottomPad,
        int itemIconSize,
        int itemCountGap,
        int itemCountTop,
        int stampLeft,
        int stampTop,
        double zoomMin,
        double zoomMax,
        double zoomStep,
        int panMargin,
        int toastWidth,
        int toastHeight,
        int toastMillis,
        int toastInset) {
    public static QuestMapTuning current() {
        return UiTheme.current().questMap();
    }

    public static QuestMapTuning of(ThemeData d) {
        return new QuestMapTuning(
                d.integer("chapterTabWidth", 1),
                d.integer("chapterTabTop", 0),
                d.integer("chapterTabGap", 0),
                d.integer("stepColumns", 1),
                d.integer("stepMinWidth", 1),
                d.integer("stepGap", 0),
                d.integer("stepHeight", 1),
                d.integer("stepPitch", 1),
                d.integer("stepTextInset", 0),
                d.integer("stepMaxLines", 1),
                d.integer("mapPad", 0),
                d.integer("aheadGap", 0),
                d.integer("aheadLabelGap", 0),
                d.integer("chapterLinkInset", 0),
                d.integer("arrowHeadSize", 0),
                d.integer("dashOn", 1),
                d.integer("dashOff", 0),
                d.integer("loopRadius", 0),
                d.integer("loopPadLeft", 0),
                d.integer("loopPadTop", 0),
                d.integer("loopPadRight", 0),
                d.integer("loopPadBottom", 0),
                d.integer("redactionBarPercent1", 1, 100),
                d.integer("redactionBarPercent2", 1, 100),
                d.integer("redactionBarPercent3", 1, 100),
                d.integer("classifiedStampDegrees", -360, 360),
                d.integer("noteWidth", 0),
                d.integer("noteInset", 0),
                d.integer("cardWidth", 1),
                d.integer("cardTop", -1000, 1000),
                d.integer("cardMinY", 0),
                d.integer("cardRightInset", 0),
                d.integer("cardInset", 0),
                d.integer("cardHeadTop", 0),
                d.integer("cardHeadRule", 0),
                d.integer("cardTextTop", 0),
                d.integer("cardRuleTop", 0),
                d.integer("cardRuleBottom", 0),
                d.integer("cardItemPitch", 0),
                d.integer("cardItemRow", 0),
                d.integer("cardItemInset", 0),
                d.integer("cardBottomPad", 0),
                d.integer("itemIconSize", 1),
                d.integer("itemCountGap", 0),
                d.integer("itemCountTop", 0),
                d.integer("stampLeft", 0),
                d.integer("stampTop", 0),
                d.decimal("zoomMin", 0.1, 10),
                d.decimal("zoomMax", 0.1, 10),
                d.decimal("zoomStep", 0.1, 10),
                d.integer("panMargin", 0),
                d.integer("toastWidth", 1),
                d.integer("toastHeight", 1),
                d.integer("toastMillis", 1),
                d.integer("toastInset", 0));
    }
}
