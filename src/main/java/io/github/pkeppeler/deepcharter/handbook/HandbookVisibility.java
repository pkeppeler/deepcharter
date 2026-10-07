package io.github.pkeppeler.deepcharter.handbook;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * How much of a chapter a player may read (docs/SPEC.md section 5): show only the road just ahead. The chapters a charter has
 * completed and the chapter it is on are {@link #FULL}. The one after that shows its title and Directives ({@link #PREVIEW}).
 * Anything further ahead is {@link #CLASSIFIED}.
 *
 * <p>The chapter a charter is on is the first, in handbook order, that has a directive not yet completed. A directive completed
 * out of order does not move it: later chapters stay hidden until every earlier one is done.
 */
public enum HandbookVisibility {
	FULL,
	PREVIEW,
	CLASSIFIED;

	/**
	 * The visibility of each chapter.
	 *
	 * @param chapters  the directive ids of each chapter, in handbook order
	 * @param completed the directives the charter has completed
	 * @return one visibility per chapter, in the same order
	 */
	public static List<HandbookVisibility> of(List<? extends Collection<Identifier>> chapters, Set<Identifier> completed) {
		int current = chapters.size();
		for (int index = 0; index < chapters.size(); index++) {
			if (!completed.containsAll(chapters.get(index))) {
				current = index;
				break;
			}
		}
		List<HandbookVisibility> result = new ArrayList<>(chapters.size());
		for (int index = 0; index < chapters.size(); index++) {
			result.add(index <= current ? FULL : index == current + 1 ? PREVIEW : CLASSIFIED);
		}
		return result;
	}
}
