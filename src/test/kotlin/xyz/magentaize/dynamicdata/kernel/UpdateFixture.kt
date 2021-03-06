package xyz.magentaize.dynamicdata.kernel

import xyz.magentaize.dynamicdata.cache.Change
import xyz.magentaize.dynamicdata.cache.ChangeReason
import xyz.magentaize.dynamicdata.domain.Person
import org.amshove.kluent.`should not be`
import org.amshove.kluent.invoking
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldThrow
import kotlin.test.Test
import java.lang.IllegalArgumentException

internal class UpdateFixture {
    @Test
    fun add() {
        val person = Person("Person", 10)
        val update = Change(ChangeReason.Add, "Person", person)

        update.key shouldBe "Person"
        update.reason shouldBe ChangeReason.Add
        update.current shouldBe person
        update.previous shouldBe Optional.empty()
    }

    @Test
    fun remove() {
        val person = Person("Person", 10)
        val update = Change(ChangeReason.Remove, "Person", person)

        update.key shouldBe "Person"
        update.reason shouldBe ChangeReason.Remove
        update.current shouldBe person
        update.previous shouldBe Optional.empty()
    }

    @Test
    fun update() {
        val current = Person("Person", 10)
        val previous = Person("Person", 9)
        val update = Change(ChangeReason.Update, "Person", current, Optional.of(previous))

        update.key shouldBe "Person"
        update.reason shouldBe ChangeReason.Update
        update.current shouldBe current
        update.previous `should not be` Optional.empty<Person>()
        update.previous.value shouldBe previous
    }

    @Test
    fun updateWillThrowIfNoPreviousValueIsSupplied() {
        val current = Person("Person", 10)

        invoking { Change(ChangeReason.Update, "Person", current) }
            .shouldThrow(IllegalArgumentException::class)
    }
}
