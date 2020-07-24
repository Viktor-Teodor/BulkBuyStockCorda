package com.template

import com.r3.corda.lib.tokens.money.GBP
import com.template.flows.IssueCurrencyFlow
import com.template.flows.IssueStockFlow
import com.template.flows.SellStocksFlow
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Issued
import net.corda.core.contracts.withoutIssuer
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.DOLLARS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions.Companion.invokeRpc
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.core.*
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.TestCordapp
import net.corda.testing.node.User
import net.corda.testing.node.internal.FINANCE_CONTRACTS_CORDAPP
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.TestCordappImpl
import net.corda.testing.node.internal.findCordapp
import org.junit.Test
import rx.Observable
import java.util.*
import kotlin.test.assertEquals

/**
 * Allows you to run your nodes through an IDE (as opposed to using deployNodes). Do not use in a production
 * environment.
 */
class TestFlows() {

    @JvmField
    val STOCKS_CONTRACTS_CORDAPP: TestCordappImpl = findCordapp("com.template.contracts")

    @JvmField
    val STOCK_WORKFLOWS_CORDAPP: TestCordappImpl = findCordapp("com.template.flows")

    @JvmField
    val TOKENS_CONTRACTS_CORDAPP: TestCordappImpl = findCordapp("com.r3.corda.lib.tokens.contracts")

    @JvmField
    val STOCK_CORDAPPS: Set<TestCordappImpl> = setOf(STOCKS_CONTRACTS_CORDAPP, STOCK_WORKFLOWS_CORDAPP,TOKENS_CONTRACTS_CORDAPP)


    @Test(timeout=300_000)
    fun `test issuance and selling of stocks`() {

        val stocksManagerName = CordaX500Name(organisation = "stocksManager", locality = "London", country = "GB")
        val partyAName = CordaX500Name(organisation = "partyA", locality = "London", country = "GB")
        val partyBName = CordaX500Name(organisation = "partyB", locality = "London", country = "GB")
        val partyCName = CordaX500Name(organisation = "partyC", locality = "London", country = "GB")


        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = STOCK_CORDAPPS)) {
            val stocksManagerUser = User("stocksManagerUser", "testPassword1", permissions = setOf(
                    startFlow<IssueCurrencyFlow>(), startFlow<IssueStockFlow>(),
                    invokeRpc("vaultTrackBy")
            ))

            val partyAUser = User("partyAUser", "testPassword2", permissions = setOf(
                    startFlow<SellStocksFlow>(),
                    invokeRpc("vaultTrackBy")
            ))

            val partyBUser = User("partyBUser", "testPassword3", permissions = setOf(
                    startFlow<SellStocksFlow>(),
                    invokeRpc("vaultTrackBy")
            ))

            val partyCUser = User("partyCUser", "testPassword4", permissions = setOf(
                    startFlow<SellStocksFlow>(),
                    invokeRpc("vaultTrackBy")
            ))

            val (stocksManager, partyA, partyB, partyC) = listOf(
                    startNode(providedName = stocksManagerName, rpcUsers = listOf(stocksManagerUser)),
                    startNode(providedName = partyAName, rpcUsers = listOf(partyAUser)),
                    startNode(providedName = partyBName, rpcUsers = listOf(partyBUser)),
                    startNode(providedName = partyCName, rpcUsers = listOf(partyCUser))
            ).map { it.getOrThrow() }

            val stocksManagerClient = CordaRPCClient(stocksManager.rpcAddress)
            val stocksManagerProxy: CordaRPCOps = stocksManagerClient.start("stocksManagerUser", "testPassword1").proxy

            val partyAClient = CordaRPCClient(partyA.rpcAddress)
            val partyAProxy: CordaRPCOps = partyAClient.start("partyAUser", "testPassword2").proxy

            val partyBClient = CordaRPCClient(partyB.rpcAddress)
            val partyBProxy: CordaRPCOps = partyBClient.start("partyBUser", "testPassword3").proxy

            val partyCClient = CordaRPCClient(partyC.rpcAddress)
            val partyCProxy: CordaRPCOps = partyCClient.start("partyCUser", "testPassword4").proxy


            val stocksMangerVaultUpdates: Observable<Vault.Update<Cash.State>> = stocksManagerProxy.vaultTrackBy<Cash.State>().updates
            val partyAVaultCurrencyUpdates: Observable<Vault.Update<Cash.State>> = partyAProxy.vaultTrackBy<Cash.State>().updates
            val partyBVaultCurrencyUpdates: Observable<Vault.Update<Cash.State>> = partyBProxy.vaultTrackBy<Cash.State>().updates
            val partyCVaultCurrencyUpdates: Observable<Vault.Update<Cash.State>> = partyCProxy.vaultTrackBy<Cash.State>().updates


            stocksManagerProxy.startFlow (::IssueCurrencyFlow,400,"partyB").returnValue.getOrThrow()

            partyBVaultCurrencyUpdates.expectEvents {
                expect { update ->
                    println("PartyB got vault update of $update")
                    val amount: Amount<Currency> = update.produced.first().state.data.amount.withoutIssuer()
                    assertEquals(400, amount.quantity)
                }
            }

            stocksManagerProxy.startFlow(::IssueCurrencyFlow,900,"partyC").returnValue.getOrThrow()

            partyBVaultCurrencyUpdates.expectEvents {
                expect { update ->
                    println("PartyC got vault update of $update")
                    val amount: Amount<Currency> = update.produced.first().state.data.amount.withoutIssuer()
                    assertEquals(900, amount.quantity)
                }
            }
        }
    }
}
