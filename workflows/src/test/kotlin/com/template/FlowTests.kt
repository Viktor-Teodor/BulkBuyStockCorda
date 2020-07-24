package com.template


import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.template.flows.IssueCurrencyFlow
import com.template.flows.IssueStockFlow
import com.template.flows.SellStocksFlow
import com.template.states.StockShareToken
import net.corda.core.contracts.FungibleState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.utilities.*
import net.corda.testing.core.DUMMY_BANK_A_NAME
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals


class FlowTests {

    private lateinit var mockNet: MockNetwork
    private lateinit var notaryNode: StartedMockNode
    private lateinit var partyANode: StartedMockNode
    private lateinit var partyBNode: StartedMockNode
    private lateinit var partyCNode: StartedMockNode
    private lateinit var stockManagerNode: StartedMockNode
    private lateinit var newNotaryNode: StartedMockNode
    private lateinit var partyA: Party
    private lateinit var partyB: Party
    private lateinit var partyC: Party
    private lateinit var stockManager: Party
    private lateinit var notary: Party
    private lateinit var newNotary: Party


    @Before
    fun setup() {

        val mockNetworkParameters =  MockNetworkParameters()
                .withCordappsForAllNodes(
                        listOf(
                                TestCordapp.findCordapp("com.template.contracts"),
                                TestCordapp.findCordapp("com.template.flows")
                              )
                )
                .withNotarySpecs(listOf(
                        MockNetworkNotarySpec(DUMMY_NOTARY_NAME),
                        MockNetworkNotarySpec(DUMMY_BANK_A_NAME)
                ))

        mockNet = MockNetwork(mockNetworkParameters)

        notaryNode = mockNet.notaryNodes.first()

        partyANode = mockNet.createPartyNode(CordaX500Name("partyA", "London", "GB"))
        partyBNode = mockNet.createPartyNode(CordaX500Name("partyB", "London", "GB"))
        partyCNode = mockNet.createPartyNode(CordaX500Name("partyC", "London", "GB"))
        stockManagerNode = mockNet.createPartyNode(CordaX500Name("StocksManager", "London", "GB"))

        notary = notaryNode.info.singleIdentity()

        partyA = partyANode.info.singleIdentity()
        partyB = partyBNode.info.singleIdentity()
        partyC = partyCNode.info.singleIdentity()
        stockManager = stockManagerNode.info.singleIdentity()

        newNotaryNode = mockNet.notaryNodes[1]
        newNotary = mockNet.notaryNodes[1].info.singleIdentity()

    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
        System.setProperty("net.corda.node.dbtransactionsresolver.InMemoryResolutionLimit", "0")
    }

    @Test(timeout = 300_000)
    fun issueMoneyTest(){

        val issueCurrencyFlow = IssueCurrencyFlow(100,"partyA")

        val future = stockManagerNode.startFlow(issueCurrencyFlow)

        mockNet.runNetwork()
        val transaction = future.getOrThrow()

        val outputStateData = partyANode.services.vaultService.queryBy<FungibleState<TokenType>>().states.single().state.data

        assertEquals(100, outputStateData.amount.quantity)
    }

    @Test(timeout = 300_000)
    fun `issue stocks test`(){

        val stockShareTokenTest = StockShareToken(company = "Microsoft Corporation",
                                                companyCode = "MSFT",
                                                price = 123.23,
                                                maintainer = stockManager,
                                                linearId = UniqueIdentifier())

        val issueStockFlow = IssueStockFlow(company = "Microsoft Corporation",
                                            companyCode = "MSFT",
                                            price = 123.23,
                                            amount = 10,
                                            recipientName = "partyA")

        val future = stockManagerNode.startFlow(issueStockFlow)

        mockNet.runNetwork()
        val transaction = future.getOrThrow()


        val outputStateData = partyANode.services.vaultService.queryBy<FungibleState<StockShareToken>>().states.single().state.data

        assertEquals(outputStateData.amount.token.company, stockShareTokenTest.company)
        assertEquals(outputStateData.amount.token.companyCode, stockShareTokenTest.companyCode)
        assertEquals(outputStateData.amount.token.price, stockShareTokenTest.price)
        assertEquals(outputStateData.amount.token.maintainer, stockShareTokenTest.maintainer)
    }

    @Test(timeout = 300_000)
    fun `sell stocks test`(){
        //partyA will have an amount of stocks from multiple companies
        // only those from MSFT will be sold to parties B and C which have a 30%-70% agreement

        var issueStockFlow = IssueStockFlow(company = "Microsoft Corporation",
                                            companyCode = "MSFT",
                                            price = 123.0,
                                            amount = 10,
                                            recipientName = "partyA")

        val futureMSFTStocks = stockManagerNode.startFlow(issueStockFlow)

        issueStockFlow = IssueStockFlow(company = "BlackRock",
                                        companyCode = "BLK",
                                        price = 512.0,
                                        amount = 20,
                                        recipientName = "partyA")

        val futureBLKStocks = stockManagerNode.startFlow(issueStockFlow)

        //PartyB would need 369 pounds for its part
        var issueCurrencyFlow = IssueCurrencyFlow(400, "partyB")

        val futureGBPPartyB = stockManagerNode.startFlow(issueCurrencyFlow)

        //PartyC would need 861 pounds for its part
        issueCurrencyFlow = IssueCurrencyFlow(900, "partyB")

        val futureGBPPartyC = stockManagerNode.startFlow(issueCurrencyFlow)

        //call the sell flow, partyA has to start it

        val sellFLow = SellStocksFlow("MSFT", Pair("partyB", 30.0), Pair("partyC", 70.0))

        partyANode.startFlow(sellFLow)

        mockNet.runNetwork()

        //test that partyA got the right amount of money
        val listOfMoney = partyANode.services.vaultService.queryBy<FungibleState<TokenType>>().states

        assertEquals(1230, listOfMoney[0].state.data.amount.quantity+listOfMoney[1].state.data.amount.quantity)

        //test that partyB got the right amount of the needed shares
        val sharesOfPartyB = partyBNode.services.vaultService.queryBy<FungibleState<StockShareToken>>().states.single()

        assertEquals("Microsoft Corporation", sharesOfPartyB.state.data.amount.token.company)
        assertEquals("MSFT", sharesOfPartyB.state.data.amount.token.companyCode)
        assertEquals(123.0, sharesOfPartyB.state.data.amount.token.price)
        assertEquals(3, sharesOfPartyB.state.data.amount.quantity)

        val sharesOfPartyC = partyCNode.services.vaultService.queryBy<FungibleState<StockShareToken>>().states.single()

        assertEquals("Microsoft Corporation", sharesOfPartyC.state.data.amount.token.company)
        assertEquals("MSFT", sharesOfPartyC.state.data.amount.token.companyCode)
        assertEquals(123.0, sharesOfPartyC.state.data.amount.token.price)
        assertEquals(7, sharesOfPartyC.state.data.amount.quantity)

    }

   }
