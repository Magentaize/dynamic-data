package xyz.magentaize.dynamicdata.list

import xyz.magentaize.dynamicdata.domain.Person
import xyz.magentaize.dynamicdata.kernel.NotifyPropertyChanged
import xyz.magentaize.dynamicdata.list.test.asAggregator
import io.reactivex.rxjava3.schedulers.TestScheduler
import org.amshove.kluent.`should not be equal to`
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import xyz.magentaize.dynamicdata.kernel.PropertyChangedDelegate
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.time.seconds

internal class AutoRefreshFixture {
    @Test
    fun autoRefresh() {
        val items = Person.make100People()
        val list = SourceList<Person>()
        val results = list.connect().autoRefresh(Person::age).asAggregator()

        list.addRange(items)

        results.data.size shouldBeEqualTo 100
        results.messages.size shouldBeEqualTo 1

        items[0].age = 10
        results.data.size shouldBeEqualTo 100
        results.messages.size shouldBeEqualTo 2
        results.messages[1].first().reason shouldBeEqualTo ListChangeReason.Refresh

        //remove an item and check no change is fired
        val toRemove = items[1]

        list.remove(toRemove)
        results.data.size shouldBeEqualTo 99
        results.messages.size shouldBeEqualTo 3

        toRemove.age = 100
        results.messages.size shouldBeEqualTo 3

        //add it back in and check it updates
        list.add(toRemove)
        results.messages.size shouldBeEqualTo 4

        toRemove.age = 101
        results.messages.size shouldBeEqualTo 5
        results.messages.last().first().reason shouldBeEqualTo ListChangeReason.Refresh
    }

    @Test
    fun autoRefreshBatched() {
        val scheduler = TestScheduler()
        val items = Person.make100People().toList()
        val list = SourceList<Person>()
        val result = list.connect().autoRefresh(Person::age, 1.seconds, scheduler = scheduler).asAggregator()

        list.addRange(items)
        result.data.size shouldBeEqualTo 100
        result.messages.size shouldBeEqualTo 1

        items.drop(50)
            .forEach { it.age = it.age + 1 }

        scheduler.advanceTimeBy(1, TimeUnit.SECONDS)

        result.messages.size shouldBeEqualTo 2
        result.messages[1].refreshes shouldBeEqualTo 50
    }

    @Test
    fun autoRefreshFilter() {
        val items = Person.make100AgedPeople().toList()
        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .filterItem { it.age > 50 }
            .asAggregator()

        list.addRange(items)
        result.data.size shouldBeEqualTo 50
        result.messages.size shouldBeEqualTo 1

        items[0].age = 60
        result.data.size shouldBeEqualTo 51
        result.messages.size shouldBeEqualTo 2
        result.messages[1].first().reason shouldBeEqualTo ListChangeReason.Add

        items[0].age = 21
        result.data.size shouldBeEqualTo 50
        result.messages.last().first().reason shouldBeEqualTo ListChangeReason.Remove
        items[0].age = 60

        items[60].age = 160
        result.data.size shouldBeEqualTo 51
        result.messages.size shouldBeEqualTo 5
        result.messages.last().first().reason shouldBeEqualTo ListChangeReason.Replace

        val toRemove = items[65]
        list.remove(toRemove)
        result.data.size shouldBeEqualTo 50
        result.messages.size shouldBeEqualTo 6

        toRemove.age = 100
        result.messages.size shouldBeEqualTo 6

        list.add(toRemove)
        result.messages.size shouldBeEqualTo 7

        toRemove.age = 101
        result.messages.size shouldBeEqualTo 8
        result.messages.last().first().reason shouldBeEqualTo ListChangeReason.Replace
    }

    data class TransformedPerson(
        val person: Person,
        val index: Int
    )

    @Test
    fun autoRefreshTransform() {
        val items = Person.make100AgedPeople().toList()
        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .transformWithIndex { p, idx -> TransformedPerson(p, idx) }
            .asAggregator()

        list.addRange(items)
        result.data.size shouldBeEqualTo 100
        result.messages.size shouldBeEqualTo 1

        items[0].age = 60
        result.messages.size shouldBeEqualTo 2
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().first().item.reason shouldBeEqualTo ListChangeReason.Refresh
        result.messages.last().first().item.current.index shouldBeEqualTo 0

        items[60].age = 160
        result.messages.size shouldBeEqualTo 3
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().first().item.reason shouldBeEqualTo ListChangeReason.Refresh
        result.messages.last().first().item.current.index shouldBeEqualTo 60
    }

    @Test
    fun autoRefreshSort() {
        val items = Person.make100AgedPeople()
            .sortedByDescending { it.age }
            .toList()
        val comparator = compareBy<Person> { it.age }

        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .sort(compareBy { it.age })
            .asAggregator()

        fun checkOrder() {
            val sorted = items.sortedWith(comparator).toSet()
            result.data.items.toSet() shouldBeEqualTo sorted
        }

        list.addRange(items)
        result.data.size shouldBeEqualTo 100
        result.messages.size shouldBeEqualTo 1
        checkOrder()

        items[0].age = 60
        checkOrder()
        result.messages.size shouldBeEqualTo 2
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().moves shouldBeEqualTo 1

        items[90].age = -1
        checkOrder()
        result.messages.size shouldBeEqualTo 3
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().moves shouldBeEqualTo 1

        items[50].age = 49
        checkOrder()
        result.messages.size shouldBeEqualTo 4
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().moves shouldBeEqualTo 0

        items[50].age = 51
        checkOrder()
        result.messages.size shouldBeEqualTo 5
        result.messages.last().refreshes shouldBeEqualTo 1
        result.messages.last().moves shouldBeEqualTo 1
    }

    @Test
    fun autoRefreshGroupOnMutable() {
        val items = Person.make100AgedPeople().toList()
        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .groupOnMutable { it.age % 10 }
            .asAggregator()

        fun checkContent() {
            items.groupBy { it.age % 10 }.forEach {
                val childGroup = result.data.items.single { g -> g.key == it.key }
                val expected = it.value.sortedBy { p -> p.name }
                val actual = childGroup.list.items.sortedBy { p -> p.name }
                actual.toList() shouldBeEqualTo expected.toList()
            }
        }

        list.addRange(items)
        result.data.size shouldBeEqualTo 10
        result.messages.size shouldBeEqualTo 1
        checkContent()

        //move person from group 1 to 2
        items[0].age = items[0].age + 1
        checkContent()

        //change the value and move to a grouping which does not yet exist
        items[1].age = -1
        result.data.size shouldBeEqualTo 11
        result.data.items.last().key shouldBeEqualTo -1
        result.data.items.last().list.size shouldBeEqualTo 1
        result.data.items.first().list.size shouldBeEqualTo 9
        checkContent()

        //put the value back where it was and check the group was removed
        items[1].age = 1
        result.data.size shouldBeEqualTo 10
        checkContent()

        val groupOf3 = result.data.items.elementAt(2)

        var changes: ChangeSet<Person> = ChangeSet.empty()
        groupOf3.list.connect().subscribe { changes = it }

        //refresh an item which makes it belong to the same group - should then propagate a refresh
        items[2].age = 13
        changes `should not be equal to` null
        changes.size shouldBeEqualTo 1
        changes.first().reason shouldBeEqualTo ListChangeReason.Replace
        changes.first().item.current shouldBe items[2]
    }

    @Test
    fun autoRefreshGroup() {
        val items = Person.make100AgedPeople().toList()
        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .groupOn { it.age % 10 }
            .asAggregator()

        fun checkContent() {
            items.groupBy { it.age % 10 }.forEach {
                val childGroup = result.data.items.single { g -> g.key == it.key }
                val expected = it.value.sortedBy { p -> p.name }
                val actual = childGroup.items.sortedBy { p -> p.name }
                actual.toList() shouldBeEqualTo expected.toList()
            }
        }

        list.addRange(items)
        result.data.size shouldBeEqualTo 10
        result.messages.size shouldBeEqualTo 1
        checkContent()

        //move person from group 1 to 2
        items[0].age = items[0].age + 1
        checkContent()

        //change the value and move to a grouping which does not yet exist
        items[1].age = -1
        result.data.size shouldBeEqualTo 11
        result.data.items.last().key shouldBeEqualTo -1
        result.data.items.last().size shouldBeEqualTo 1
        result.data.items.first().size shouldBeEqualTo 9
        checkContent()

        //put the value back where it was and check the group was removed
        items[1].age = 1
        result.data.size shouldBeEqualTo 10
        result.messages.size shouldBeEqualTo 4
        checkContent()

        //refresh an item which makes it belong to the same group - should then propagate a refresh
        items[2].age = 13
        checkContent()
        result.messages.size shouldBeEqualTo 5
    }

    @Test
    fun autoRefreshDistinct() {
        val items = Person.make100AgedPeople().toList()
        val list = SourceList<Person>()
        val result = list.connect()
            .autoRefresh(Person::age)
            .distinctValues { it.age / 10 }
            .asAggregator()

        list.addRange(items)
        result.data.size shouldBeEqualTo 11
        result.messages.size shouldBeEqualTo 1

        //update an item which did not match the filter and does so after change
        items[50].age = 500
        result.data.size shouldBeEqualTo 12
        result.messages.last().first().reason shouldBeEqualTo ListChangeReason.Add
        result.messages.last().first().item.current shouldBeEqualTo 50
    }

    @Test
    fun autoRefreshMap() {
        val items = (1..10).map { SelectableItem(it) }.toList()
        val list = SourceList<SelectableItem>()
        val result = list.connect()
            .autoRefresh()
            .filterItem { it.isSelected }
            .asObservableList()

        list.addRange(items)
        result.size shouldBeEqualTo 0

        items[0].isSelected = true
        result.size shouldBeEqualTo 1

        items[1].isSelected = true
        result.size shouldBeEqualTo 2

        list.removeRange(0, 2)
        result.size shouldBeEqualTo 0
    }

    class SelectableItem(val id: Int) : NotifyPropertyChanged {
        var isSelected: Boolean by PropertyChangedDelegate(false)
        private var _isDisposed = false

        override fun dispose() {
            super.dispose()
            _isDisposed = true
        }

        override fun isDisposed(): Boolean =
            _isDisposed
    }

    @Test
    fun refreshTransformAsList() {
        val list = SourceList<Example>()
        val result = list.connect()
            .autoRefresh(Example::value)
            .transform({ it -> it.value }, true)
            .asObservableList()

        val e = Example()
        list.add(e)
        e.value = 1
        result.items.first() shouldBeEqualTo 1
    }

    class Example : NotifyPropertyChanged {
        var value: Int by PropertyChangedDelegate(0)

        private var _isDisposed = false

        override fun dispose() {
            super.dispose()
            _isDisposed = true
        }

        override fun isDisposed(): Boolean =
            _isDisposed
    }
}
