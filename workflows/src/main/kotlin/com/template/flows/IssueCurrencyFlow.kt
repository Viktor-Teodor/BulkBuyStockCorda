package com.template.flows


import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.contracts.utilities.withNotary
import com.r3.corda.lib.tokens.money.GBP
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.issue.IssueTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokensHandler
import com.template.states.StockShareToken
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import java.util.*

@InitiatingFlow
@StartableByRPC
class IssueCurrencyFlow(
        val amount: Long,
        val recipientName: String
) : FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val recipientCordaName = CordaX500Name(
                organisation = "$recipientName",
                locality = "London",
                country = "GB")

        val recipient: Party = serviceHub.identityService.wellKnownPartyFromX500Name(recipientCordaName) ?:
        throw IllegalArgumentException("Couldn't find counterparty for $recipientName in identity service")

        val session = initiateFlow(recipient)

        return subFlow(IssueTokensFlow(amount.GBP issuedBy ourIdentity heldBy recipient, listOf(session)))
    }
}

@InitiatedBy(IssueCurrencyFlow::class)
class IssueCurrencyFlowResponder(val counterpartySession: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        return subFlow(IssueTokensFlowHandler(counterpartySession))
    }

}