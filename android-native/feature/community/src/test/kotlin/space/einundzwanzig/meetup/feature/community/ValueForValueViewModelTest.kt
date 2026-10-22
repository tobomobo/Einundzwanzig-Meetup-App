package space.einundzwanzig.meetup.feature.community

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import space.einundzwanzig.meetup.data.community.v4v.V4V_LIGHTNING_ADDRESS
import space.einundzwanzig.meetup.data.community.v4v.V4vErrorKind
import space.einundzwanzig.meetup.data.community.v4v.V4vResult
import space.einundzwanzig.meetup.data.community.v4v.V4vService
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueAction
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueEvent
import space.einundzwanzig.meetup.feature.community.v4v.ValueForValueViewModel

class ValueForValueViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val service = FakeV4vService()
    private val vm = ValueForValueViewModel(service)

    @Test
    fun `amount field keeps digits only`() {
        vm.onAction(ValueForValueAction.AmountChanged("2.1a00,"))
        assertEquals("2100", vm.state.value.amount)
    }

    @Test
    fun `empty or zero amount shows invalid amount without a request`() {
        vm.onAction(ValueForValueAction.Donate)
        assertEquals(V4vErrorKind.InvalidAmount, vm.state.value.error)
        vm.onAction(ValueForValueAction.AmountChanged("0"))
        assertNull("typing clears the error", vm.state.value.error)
        vm.onAction(ValueForValueAction.Donate)
        assertEquals(V4vErrorKind.InvalidAmount, vm.state.value.error)
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun `donate requests an invoice with the V4V comment and opens it`() = runTest {
        vm.onAction(ValueForValueAction.AmountChanged("2100"))
        vm.onAction(ValueForValueAction.Donate)
        assertEquals(listOf(Triple(2100L, V4V_LIGHTNING_ADDRESS, "V4V – 21Meetup")), service.calls)
        assertEquals(ValueForValueEvent.OpenInvoice("lnbc1"), vm.events.first())
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `loading while the request runs, double tap ignored`() {
        val gate = CompletableDeferred<V4vResult>()
        var calls = 0
        val slow = ValueForValueViewModel(object : V4vService {
            override suspend fun createInvoice(amountSats: Long, address: String, comment: String?): V4vResult {
                calls++
                return gate.await()
            }
        })
        slow.onAction(ValueForValueAction.AmountChanged("21"))
        slow.onAction(ValueForValueAction.Donate)
        assertTrue(slow.state.value.loading)
        slow.onAction(ValueForValueAction.Donate)
        assertEquals(1, calls)
        gate.complete(V4vResult.Failure("unreachable"))
        assertFalse(slow.state.value.loading)
        assertEquals(V4vErrorKind.Unreachable, slow.state.value.error)
    }

    @Test
    fun `failure codes map to the error kinds`() {
        mapOf(
            "below_min" to V4vErrorKind.BelowMin,
            "above_max" to V4vErrorKind.AboveMax,
            "invoice_failed" to V4vErrorKind.Generic,
            "Some server reason" to V4vErrorKind.Generic,
        ).forEach { (code, kind) ->
            service.result = V4vResult.Failure(code)
            vm.onAction(ValueForValueAction.AmountChanged("5"))
            vm.onAction(ValueForValueAction.Donate)
            assertEquals(code, kind, vm.state.value.error)
        }
    }

    @Test
    fun `no wallet shows the sheet, copy closes it and emits the copy event`() = runTest {
        vm.onAction(ValueForValueAction.WalletUnavailable("lnbc1"))
        assertEquals("lnbc1", vm.state.value.noWalletInvoice)
        vm.onAction(ValueForValueAction.CopyInvoice)
        assertNull(vm.state.value.noWalletInvoice)
        assertEquals(ValueForValueEvent.CopyInvoice("lnbc1"), vm.events.first())
    }

    @Test
    fun `dismissing the sheet clears it`() {
        vm.onAction(ValueForValueAction.WalletUnavailable("lnbc1"))
        vm.onAction(ValueForValueAction.DismissSheet)
        assertNull(vm.state.value.noWalletInvoice)
    }
}
