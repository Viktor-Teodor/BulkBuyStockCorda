package com.template.contracts

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import com.template.states.StockShareToken
import net.corda.core.transactions.LedgerTransaction



/**
 * This doesn't do anything over and above the [EvolvableTokenContract].
 */
class StockShareTokenContract : EvolvableTokenContract(){
    override fun additionalCreateChecks(tx: LedgerTransaction) {
        val inputs : List<StockShareToken> = tx.inputsOfType(StockShareToken::class.java)
        val outputs : List<StockShareToken> = tx.outputsOfType(StockShareToken::class.java)

        val hasAllPositivePrices : Boolean = inputs.asSequence().all { it.price > 0 } &&
                outputs.asSequence().all { it.price > 0}


        require(hasAllPositivePrices) { "All prices should be positive"}

    }
    override fun additionalUpdateChecks(tx: LedgerTransaction){
        val inputs : StockShareToken = tx.inputsOfType(StockShareToken::class.java) as StockShareToken
        val outputs : StockShareToken = tx.outputsOfType(StockShareToken::class.java) as StockShareToken

        require(inputs.company == outputs.company) {"The company of this stock share cannot change"}
        require(inputs.companyCode == outputs.company) {"The company code cannot change"}


    }
}