package net.corda.bank

import joptsimple.OptionParser
import net.corda.bank.api.BankOfCordaClientApi
import net.corda.bank.api.BankOfCordaWebApi.IssueRequestParams
import net.corda.core.identity.CordaX500Name
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.finance.flows.CashConfigDataFlow
import net.corda.finance.flows.CashExitFlow
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.finance.flows.CashPaymentFlow
import net.corda.node.services.Permissions.Companion.all
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.nodeapi.User
import net.corda.testing.BOC
import net.corda.testing.DUMMY_NOTARY
import net.corda.testing.driver.driver
import kotlin.system.exitProcess

/**
 * This entry point allows for command line running of the Bank of Corda functions on nodes started by BankOfCordaDriver.kt.
 */
fun main(args: Array<String>) {
    BankOfCordaDriver().main(args)
}

const val BANK_USERNAME = "bankUser"
const val BIGCORP_USERNAME = "bigCorpUser"

val BIGCORP_LEGAL_NAME = CordaX500Name(organisation = "BigCorporation", locality = "New York", country = "US")

private class BankOfCordaDriver {
    enum class Role {
        ISSUE_CASH_RPC,
        ISSUE_CASH_WEB,
        ISSUER
    }

    fun main(args: Array<String>) {
        val parser = OptionParser()
        val roleArg = parser.accepts("role").withRequiredArg().ofType(Role::class.java).describedAs("[ISSUER|ISSUE_CASH_RPC|ISSUE_CASH_WEB]")
        val quantity = parser.accepts("quantity").withOptionalArg().ofType(Long::class.java)
        val currency = parser.accepts("currency").withOptionalArg().ofType(String::class.java).describedAs("[GBP|USD|CHF|EUR]")
        val options = try {
            parser.parse(*args)
        } catch (e: Exception) {
            println(e.message)
            printHelp(parser)
            exitProcess(1)
        }

        // What happens next depends on the role.
        // The ISSUER will launch a Bank of Corda node
        // The ISSUE_CASH will request some Cash from the ISSUER on behalf of Big Corporation node
        val role = options.valueOf(roleArg)!!

        try {
            when (role) {
                Role.ISSUER -> {
                    driver(isDebug = true, extraCordappPackagesToScan = listOf("net.corda.finance.contracts.asset"), waitForAllNodesToFinish = true) {
                        val bankUser = User(
                                BANK_USERNAME,
                                "test",
                                permissions = setOf(
                                        startFlow<CashPaymentFlow>(),
                                        startFlow<CashConfigDataFlow>(),
                                        startFlow<CashExitFlow>(),
                                        startFlow<CashIssueAndPaymentFlow>(),
                                        startFlow<CashConfigDataFlow>(),
                                        all()
                                ))
                        val bankOfCorda = startNode(
                                providedName = BOC.name,
                                rpcUsers = listOf(bankUser))
                        val bigCorpUser = User(BIGCORP_USERNAME, "test",
                                permissions = setOf(
                                        startFlow<CashPaymentFlow>(),
                                        startFlow<CashConfigDataFlow>(),
                                        all()))
                        startNode(providedName = BIGCORP_LEGAL_NAME, rpcUsers = listOf(bigCorpUser))
                        startWebserver(bankOfCorda.get())
                    }
                }
                else -> {
                    val requestParams = IssueRequestParams(options.valueOf(quantity), options.valueOf(currency), BIGCORP_LEGAL_NAME,
                            "1", BOC.name, DUMMY_NOTARY.name)
                    when(role) {
                        Role.ISSUE_CASH_RPC -> {
                            println("Requesting Cash via RPC ...")
                            val result = BankOfCordaClientApi(NetworkHostAndPort("localhost", 10004)).requestRPCIssue(requestParams)
                            println("Success!! You transaction receipt is ${result.tx.id}")
                        }
                        Role.ISSUE_CASH_WEB -> {
                            println("Requesting Cash via Web ...")
                            val result = BankOfCordaClientApi(NetworkHostAndPort("localhost", 10005)).requestWebIssue(requestParams)
                            if (result)
                                println("Successfully processed Cash Issue request")
                        }
                        else -> {
                            throw IllegalArgumentException("Unrecognized role: " + role)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Exception occurred: $e \n ${e.printStackTrace()}")
            exitProcess(1)
        }
    }

    fun printHelp(parser: OptionParser) {
        println("""
        Usage: bank-of-corda --role ISSUER
               bank-of-corda --role (ISSUE_CASH_RPC|ISSUE_CASH_WEB) --quantity <quantity> --currency <currency>

        Please refer to the documentation in docs/build/index.html for more info.

        """.trimIndent())
        parser.printHelpOn(System.out)
    }
}

