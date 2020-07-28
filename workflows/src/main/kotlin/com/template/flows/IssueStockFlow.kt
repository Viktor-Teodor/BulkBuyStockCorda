package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.contracts.utilities.withNotary
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokensHandler
import com.template.states.StockShareToken
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker

@InitiatingFlow
@StartableByRPC
class IssueStockFlow(
        val company : String,
        val companyCode : String,
        val price : Double,
        val amount: Long,
        val recipientName: String
) : FlowLogic<SignedTransaction>() {

    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val stockManager: CordaX500Name = CordaX500Name(
                organisation = "stocksManager",
                locality = "London",
                country = "GB")

        val namedStockManager: Party = serviceHub.identityService.wellKnownPartyFromX500Name(stockManager) ?:
        throw IllegalArgumentException("Couldn't find counter party for StocksManager in identity service")



        val stockShares = StockShareToken(company ="$company",
                                          companyCode = "$companyCode",
                                          maintainer = namedStockManager,
                                          price = price,
                                          linearId = UniqueIdentifier(),
                                          fractionDigits = 4)

        subFlow(CreateEvolvableTokens(stockShares withNotary notary))

        val recipientCordaName: CordaX500Name = CordaX500Name(
                organisation = "$recipientName",
                locality = "London",
                country = "GB")

        val recipient: Party = serviceHub.identityService.wellKnownPartyFromX500Name(recipientCordaName) ?:
        throw IllegalArgumentException("Couldn't find counterparty for $recipientName in identity service")

        //create a pointer to the evolvable token
        val token = stockShares.toPointer<StockShareToken>()

        var test = IssuedTokenType(ourIdentity, token)

        val session = initiateFlow(recipient)

        // Starting this flow with a new flow session.
        val issueTokensFlow = IssueTokensFlow(amount of token issuedBy ourIdentity heldBy recipient, listOf(session))

        return subFlow(issueTokensFlow)
    }
}

@InitiatedBy(IssueStockFlow::class)
class IssueStockFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {

        return subFlow(IssueTokensFlowHandler(counterpartySession))
    }
}