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
 * @param aheadGap space above the redaction bars and the note
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
 * @param anyCueGap space between an any-of slot's count and its "any" cue
 * @param cycleTicks how long an any-of slot's icon shows each alternative
 * @param itemCountTop distance from the top of an item icon to its count's text
 * @param stampLeft distance from the text column's left edge to the centre of the COMPLETE or COMING SOON stamp
 * @param stampTop distance from the sheet's first line to the centre of that stamp
 * @param toastWidth width of the step-done popup
 * @param toastHeight least height of the step-done popup; it grows to fit a step that wraps to more lines
 * @param toastMillis how long the step-done popup shows
 * @param toastInset space between the step-done popup's edge and its text
 * @param toastMargin space between the step-done popup and the left, right, top and bottom edges of the screen
 * @param toastGap the least space between the step-done popup and any part of the HUD it keeps clear of
 * @param toastStaleMillis how long after its last frame a step-done popup still counts as on screen, to the popups placed under it
 * @param toastWaitMillis how long a step-done popup waits for room before it gives up: on a GUI with no clear place for it, it
 *     waits, and its {@code toastMillis} do not run while it does
 * @param slipWidth width of the new-spec-sheet slip
 * @param slipHeight least height of the new-spec-sheet slip; it grows to fit a name that wraps to more lines
 * @param slipMillis how long the new-spec-sheet slip shows
 * @param barsNoteGap space between the redaction bars and the margin note under them
 * @param dropLinkPercent where the black link from the row above drops to the bars, in percent of a step box's width
 * @param stampXPercent where the CLASSIFIED stamp's centre sits along the first bar, in percent of a step box's width
 * @param stampYOffset how far below the first bar's middle the CLASSIFIED stamp's centre sits
 * @param stampReach how far the turned CLASSIFIED stamp reaches below its centre
 * @param strikeLift how far above the middle of its count a met count's strike-through runs
 * @param loopTailStart how far in from the loop's right edge the pen's closing stroke begins
 * @param loopTailMid how far in from the loop's right edge the closing stroke's bend is
 * @param loopTailEnd how far in from the loop's right edge the closing stroke ends
 * @param loopTailRise how far above the loop's top edge the closing stroke's bend rises
 * @param loopTailEndRise how far above the loop's top edge the closing stroke ends
 * @param headingMinWidth narrowest the chapter label and title wrap to beside the index card
 * @param headingCardGap space between a wrapped chapter heading and the index card
 * @param headingGap space between the chapter label and the title
 * @param ruleGap space between the title and the first row below its rule
 * @param windowMargin space kept between the sheet and the window's sides
 * @param tabEdgeGap space kept between the chapter tabs and the window's right edge
 * @param cardItemsGap space between the card's instruction and its first row of items
 * @param pencilRowPitch tallest a row of the pencil sheet is, from the top of its boxes to the top of the next row's
 * @param pencilRowGap space between two rows of the pencil sheet
 * @param pencilSourcePercent width of the box of the Note that draws a path, in percent of the sheet's text width
 * @param pencilArrowMin shortest the dashed pencil line between a path's two boxes is drawn, with its x
 * @param pencilBoxInset space between a pencil box's edge and its text
 * @param pencilMarkSize how far the arms of the x at a payoff reach from its middle
 * @param pencilWobble how far, in pixels, the pencil boxes' corners overshoot and their sides drift: the hand in a hand-drawn box
 * @param pencilRingExtra how far the ring of a reached path stands out past the arms of its x
 * @param pencilTextTop space between a pencil box's top edge and its first line of text
 * @param pencilLineGap space between two lines of text in a pencil box
 * @param pencilTipGap space between the tip of the dashed pencil line and the arms of the x it points at
 * @param pencilStartGap space between a Note's box and the start of the dashed pencil line
 * @param pencilMarkGap space between the x and the payoff box
 * @param clickCueSize how far each arm of the chevron on a thing a click opens reaches back from its tip, across and up or down
 *     ({@link ClickCue})
 * @param clickCueInset space between the tip of that chevron and the right edge of the thing it marks
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
        int anyCueGap,
        int cycleTicks,
        int itemCountTop,
        int stampLeft,
        int stampTop,
        int toastWidth,
        int toastHeight,
        int toastMillis,
        int toastInset,
        int toastMargin,
        int toastGap,
        int toastStaleMillis,
        int toastWaitMillis,
        int slipWidth,
        int slipHeight,
        int slipMillis,
        int barsNoteGap,
        int dropLinkPercent,
        int stampXPercent,
        int stampYOffset,
        int stampReach,
        int strikeLift,
        int loopTailStart,
        int loopTailMid,
        int loopTailEnd,
        int loopTailRise,
        int loopTailEndRise,
        int headingMinWidth,
        int headingCardGap,
        int headingGap,
        int ruleGap,
        int windowMargin,
        int tabEdgeGap,
        int cardItemsGap,
        int pencilRowPitch,
        int pencilRowGap,
        int pencilSourcePercent,
        int pencilArrowMin,
        int pencilBoxInset,
        int pencilMarkSize,
        int pencilWobble,
        int pencilRingExtra,
        int pencilTextTop,
        int pencilLineGap,
        int pencilTipGap,
        int pencilStartGap,
        int pencilMarkGap,
        int clickCueSize,
        int clickCueInset) {
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
                d.integer("anyCueGap", 0),
                d.integer("cycleTicks", 1),
                d.integer("itemCountTop", 0),
                d.integer("stampLeft", 0),
                d.integer("stampTop", 0),
                d.integer("toastWidth", 1),
                d.integer("toastHeight", 1),
                d.integer("toastMillis", 1),
                d.integer("toastInset", 0),
                d.integer("toastMargin", 0),
                d.integer("toastGap", 0),
                d.integer("toastStaleMillis", 1),
                d.integer("toastWaitMillis", 1),
                d.integer("slipWidth", 1),
                d.integer("slipHeight", 1),
                d.integer("slipMillis", 1),
                d.integer("barsNoteGap", 0),
                d.integer("dropLinkPercent", 0),
                d.integer("stampXPercent", 0),
                d.integer("stampYOffset", 0),
                d.integer("stampReach", 0),
                d.integer("strikeLift", 0),
                d.integer("loopTailStart", 0),
                d.integer("loopTailMid", 0),
                d.integer("loopTailEnd", 0),
                d.integer("loopTailRise", 0),
                d.integer("loopTailEndRise", 0),
                d.integer("headingMinWidth", 1),
                d.integer("headingCardGap", 0),
                d.integer("headingGap", 0),
                d.integer("ruleGap", 0),
                d.integer("windowMargin", 0),
                d.integer("tabEdgeGap", 0),
                d.integer("cardItemsGap", 0),
                d.integer("pencilRowPitch", 1),
                d.integer("pencilRowGap", 0),
                d.integer("pencilSourcePercent", 10, 60),
                d.integer("pencilArrowMin", 1),
                d.integer("pencilBoxInset", 0),
                d.integer("pencilMarkSize", 1),
                d.integer("pencilWobble", 0, 3),
                d.integer("pencilRingExtra", 0),
                d.integer("pencilTextTop", 0),
                d.integer("pencilLineGap", 0),
                d.integer("pencilTipGap", 0),
                d.integer("pencilStartGap", 0),
                d.integer("pencilMarkGap", 0),
                d.integer("clickCueSize", 1),
                d.integer("clickCueInset", 0));
    }
}
