package com.template

import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.template.flows.*
import com.template.states.StockShareToken
import jdk.nashorn.internal.parser.TokenType
import net.corda.client.rpc.CordaRPCClient
import net.corda.core.contracts.Amount
import net.corda.core.contracts.FungibleState
import net.corda.core.identity.CordaX500Name
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultTrackBy
import net.corda.core.node.services.Vault
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions.Companion.invokeRpc
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.expect
import net.corda.testing.core.expectEvents
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import net.corda.testing.node.internal.TestCordappImpl
import net.corda.testing.node.internal.findCordapp
import org.junit.Test
import rx.Observable
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
    val TOKENS_WORKFLOWS_CORDAPP: TestCordappImpl = findCordapp("com.r3.corda.lib.tokens.workflows")

    @JvmField
    val STOCK_CORDAPPS: Set<TestCordappImpl> = setOf(
        STOCKS_CONTRACTS_CORDAPP,
        STOCK_WORKFLOWS_CORDAPP,
        TOKENS_CONTRACTS_CORDAPP,
        TOKENS_WORKFLOWS_CORDAPP
    )



    @Test(timeout = 300_000)
    fun `test issuance and selling of stocks`() {

        val stocksManagerName = CordaX500Name(organisation = "stocksManager", locality = "London", country = "GB")
        val partyAName = CordaX500Name(organisation = "partyA", locality = "London", country = "GB")
        val partyBName = CordaX500Name(organisation = "partyB", locality = "London", country = "GB")
        val partyCName = CordaX500Name(organisation = "partyC", locality = "London", country = "GB")



        driver(
            DriverParameters(
                startNodesInProcess = true,
                cordappsForAllNodes = STOCK_CORDAPPS,
                networkParameters = testNetworkParameters(minimumPlatformVersion = 4)
            )
        ) {

            val stocksManagerUser = User(
                "stocksManagerUser", "testPassword1", permissions = setOf(
                    startFlow<IssueCurrencyFlow>(), startFlow<IssueStockFlow>(),
                    invokeRpc("vaultTrackBy")
                )
            )

            val partyAUser = User(
                "partyAUser", "testPassword2", permissions = setOf("ALL")
            )

            val partyBUser = User(
                "partyBUser", "testPassword3", permissions = setOf("ALL")
            )

            val partyCUser = User(
                "partyCUser", "testPassword4", permissions = setOf("ALL")
            )

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


            val stocksMangerVaultUpdates: Observable<Vault.Update<FungibleState<TokenType>>> =
                stocksManagerProxy.vaultTrackBy<FungibleState<TokenType>>().updates
            val partyAVaultCurrencyUpdates: Observable<Vault.Update<FungibleState<TokenType>>> =
                partyAProxy.vaultTrackBy<FungibleState<TokenType>>().updates
            val partyBVaultCurrencyUpdates: Observable<Vault.Update<FungibleState<TokenType>>> =
                partyBProxy.vaultTrackBy<FungibleState<TokenType>>().updates
            val partyCVaultCurrencyUpdates: Observable<Vault.Update<FungibleState<TokenType>>> =
                partyCProxy.vaultTrackBy<FungibleState<TokenType>>().updates

            val partyAVaultStockUpdates: Observable<Vault.Update<FungibleState<StockShareToken>>> =
                partyAProxy.vaultTrackBy<FungibleState<StockShareToken>>().updates
            val partyBVaultStockUpdates: Observable<Vault.Update<FungibleState<StockShareToken>>> =
                partyBProxy.vaultTrackBy<FungibleState<StockShareToken>>().updates
            val partyCVaultStockUpdates: Observable<Vault.Update<FungibleState<StockShareToken>>> =
                partyCProxy.vaultTrackBy<FungibleState<StockShareToken>>().updates


           stocksManagerProxy.startFlow(::IssueCurrencyFlow, 400, "partyB").returnValue.getOrThrow()

            partyBVaultCurrencyUpdates.expectEvents {
                expect { update ->
                    println("PartyB got vault update of $update")
                    val amount: Amount<TokenType> = update.produced.first().state.data.amount
                    assertEquals(40000, amount.quantity)
                }
            }

            stocksManagerProxy.startFlow(::IssueCurrencyFlow, 900, "partyC").returnValue.getOrThrow()

            partyCVaultCurrencyUpdates.expectEvents {
                expect { update ->
                    println("PartyC got vault update of $update")
                    val amount: Amount<TokenType> = update.produced.first().state.data.amount
                    assertEquals(90000, amount.quantity)
                }
            }

            val test = stocksManagerProxy.startFlow(
                ::IssueStockFlow,
                "Microsoft Corporations",
                "MSFT",
                123.0,
                10,
                "partyA"
            ).returnValue.getOrThrow()

            partyAVaultStockUpdates.expectEvents {
                expect { update ->
                    println("PartyA got vault update of $update")
                    val amount: Amount<StockShareToken> = update.produced.first().state.data.amount
                    assertEquals(100000, amount.quantity)
                }
            }

            val listArgument = listOf(
                    Pair("partyB", 30.0),
                    Pair("partyC",70.0)
            )

            partyAProxy.startFlow(
                    ::SellStocksFlow,
                    "MSFT",
                    listArgument
            ).returnValue.getOrThrow()

            /*
            partyAVaultCurrencyUpdates.expectEvents {
                expect { update ->
                    println("PartyA got vault update of $update")
                    val amount: Amount<TokenType> = update.produced.first().state.data.amount
                    assertEquals(123000, amount.quantity)
                }
            }
            */

            /*
            partyBVaultStockUpdates.expectEvents {
                expect { update ->
                    println("PartyB got vault update of $update")
                    val amount: Amount<StockShareToken> = update.produced.first().state.data.amount
                    assertEquals(30000, amount.quantity)
                }
            }

            partyCVaultStockUpdates.expectEvents {
                expect { update ->
                    println("PartyC got vault update of $update")
                    val amount: Amount<StockShareToken> = update.produced.first().state.data.amount
                    assertEquals(70000, amount.quantity)
                }
            }

             */

        }
    }
}
