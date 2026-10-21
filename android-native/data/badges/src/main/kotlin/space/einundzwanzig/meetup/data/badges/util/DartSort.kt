package space.einundzwanzig.meetup.data.badges.util

/**
 * Dart VM `List.sort` (`dart:_internal` `Sort.sort`, Dart 3.13), ported line by line.
 *
 * Ranges of up to 33 elements (`right - left <= 32`, inclusive `right`) use a stable insertion
 * sort; longer ranges use Yaroslavskiy's dual-pivot quicksort, which is **not** stable. Using
 * this instead of Kotlin's stable `sortedWith` reproduces Dart's order exactly for every size,
 * including the order of ties, so hash chains over sorted lists (`badge_proof`, `badge_proof_v2`,
 * badge sync ordering) are byte-identical to the Flutter app (spec 00 item 10, spec 03 §7.2).
 */
object DartSort {

    private const val INSERTION_SORT_THRESHOLD = 32

    /** Sorts [list] in place exactly like Dart's `list.sort(compare)`. */
    fun <E> sort(list: MutableList<E>, compare: Comparator<in E>) {
        doSort(list, 0, list.size - 1, compare)
    }

    /** A sorted copy of [items], in Dart order (`List.of(items)..sort(compare)`). */
    fun <E> sorted(items: Iterable<E>, compare: Comparator<in E>): List<E> =
        items.toMutableList().also { sort(it, compare) }

    private fun <E> doSort(a: MutableList<E>, left: Int, right: Int, compare: Comparator<in E>) {
        if (right - left <= INSERTION_SORT_THRESHOLD) {
            insertionSort(a, left, right, compare)
        } else {
            dualPivotQuicksort(a, left, right, compare)
        }
    }

    private fun <E> insertionSort(a: MutableList<E>, left: Int, right: Int, compare: Comparator<in E>) {
        for (i in left + 1..right) {
            val el = a[i]
            var j = i
            while (j > left && compare.compare(a[j - 1], el) > 0) {
                a[j] = a[j - 1]
                j--
            }
            a[j] = el
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun <E> dualPivotQuicksort(a: MutableList<E>, left: Int, right: Int, compare: Comparator<in E>) {
        val sixth = (right - left + 1) / 6
        val index1 = left + sixth
        val index5 = right - sixth
        val index3 = (left + right) / 2
        val index2 = index3 - sixth
        val index4 = index3 + sixth

        var el1 = a[index1]
        var el2 = a[index2]
        var el3 = a[index3]
        var el4 = a[index4]
        var el5 = a[index5]

        // Sorting network for the five samples.
        if (compare.compare(el1, el2) > 0) { val t = el1; el1 = el2; el2 = t }
        if (compare.compare(el4, el5) > 0) { val t = el4; el4 = el5; el5 = t }
        if (compare.compare(el1, el3) > 0) { val t = el1; el1 = el3; el3 = t }
        if (compare.compare(el2, el3) > 0) { val t = el2; el2 = el3; el3 = t }
        if (compare.compare(el1, el4) > 0) { val t = el1; el1 = el4; el4 = t }
        if (compare.compare(el3, el4) > 0) { val t = el3; el3 = el4; el4 = t }
        if (compare.compare(el2, el5) > 0) { val t = el2; el2 = el5; el5 = t }
        if (compare.compare(el2, el3) > 0) { val t = el2; el2 = el3; el3 = t }
        if (compare.compare(el4, el5) > 0) { val t = el4; el4 = el5; el5 = t }

        val pivot1 = el2
        val pivot2 = el4

        a[index1] = el1
        a[index3] = el3
        a[index5] = el5

        a[index2] = a[left]
        a[index4] = a[right]

        var less = left + 1
        var great = right - 1

        val pivotsAreEqual = compare.compare(pivot1, pivot2) == 0
        if (pivotsAreEqual) {
            val pivot = pivot1
            var k = less
            while (k <= great) {
                val ak = a[k]
                var comp = compare.compare(ak, pivot)
                if (comp == 0) {
                    k++
                    continue
                }
                if (comp < 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    while (true) {
                        comp = compare.compare(a[great], pivot)
                        if (comp > 0) {
                            great--
                            continue
                        } else if (comp < 0) {
                            a[k] = a[less]
                            a[less++] = a[great]
                            a[great--] = ak
                            break
                        } else {
                            a[k] = a[great]
                            a[great--] = ak
                            break
                        }
                    }
                }
                k++
            }
        } else {
            var k = less
            while (k <= great) {
                val ak = a[k]
                val compPivot1 = compare.compare(ak, pivot1)
                if (compPivot1 < 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    val compPivot2 = compare.compare(ak, pivot2)
                    if (compPivot2 > 0) {
                        while (true) {
                            var comp = compare.compare(a[great], pivot2)
                            if (comp > 0) {
                                great--
                                if (great < k) break
                                continue
                            } else {
                                comp = compare.compare(a[great], pivot1)
                                if (comp < 0) {
                                    a[k] = a[less]
                                    a[less++] = a[great]
                                    a[great--] = ak
                                } else {
                                    a[k] = a[great]
                                    a[great--] = ak
                                }
                                break
                            }
                        }
                    }
                }
                k++
            }
        }

        // Move pivots into their final positions.
        a[left] = a[less - 1]
        a[less - 1] = pivot1
        a[right] = a[great + 1]
        a[great + 1] = pivot2

        doSort(a, left, less - 2, compare)
        doSort(a, great + 2, right, compare)

        if (pivotsAreEqual) return

        if (less < index1 && great > index5) {
            while (compare.compare(a[less], pivot1) == 0) less++
            while (compare.compare(a[great], pivot2) == 0) great--

            var k = less
            while (k <= great) {
                val ak = a[k]
                val compPivot1 = compare.compare(ak, pivot1)
                if (compPivot1 == 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    val compPivot2 = compare.compare(ak, pivot2)
                    if (compPivot2 == 0) {
                        while (true) {
                            var comp = compare.compare(a[great], pivot2)
                            if (comp == 0) {
                                great--
                                if (great < k) break
                                continue
                            } else {
                                comp = compare.compare(a[great], pivot1)
                                if (comp < 0) {
                                    a[k] = a[less]
                                    a[less++] = a[great]
                                    a[great--] = ak
                                } else {
                                    a[k] = a[great]
                                    a[great--] = ak
                                }
                                break
                            }
                        }
                    }
                }
                k++
            }
            doSort(a, less, great, compare)
        } else {
            doSort(a, less, great, compare)
        }
    }
}
