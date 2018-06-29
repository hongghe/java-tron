package org.tron.common.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.common.crypto.ECKey;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.config.SystemProperties;
import org.tron.common.runtime.storage.Deposit;
import org.tron.common.runtime.storage.DepositImpl;
import org.tron.common.runtime.vm.PrecompiledContracts;
import org.tron.common.runtime.vm.VM;
import org.tron.common.runtime.vm.program.InternalTransaction;
import org.tron.common.runtime.vm.program.Program;
import org.tron.common.runtime.vm.program.ProgramPrecompile;
import org.tron.common.runtime.vm.program.ProgramResult;
import org.tron.common.runtime.vm.program.invoke.ProgramInvoke;
import org.tron.common.runtime.vm.program.invoke.ProgramInvokeFactory;
import org.tron.common.utils.Utils;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.protos.Contract;
import org.tron.protos.Contract.ContractCreationContract;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;

import java.util.List;

import static org.apache.commons.lang3.ArrayUtils.isEmpty;
import static org.tron.common.runtime.vm.program.InternalTransaction.ExecuterType.*;
import static org.tron.common.runtime.vm.program.InternalTransaction.TrxType.*;

/**
 * @author Guo Yonggang
 * @since 28.04.2018
 */
public class Runtime {

    private static final Logger logger = LoggerFactory.getLogger("execute");

    SystemProperties config;

    private Transaction trx;
    private Block block = null;
    private Deposit deposit;
    private ProgramInvokeFactory programInvokeFactory = null;
    private String runtimeError;

    PrecompiledContracts.PrecompiledContract precompiledContract = null;
    private ProgramResult result = new ProgramResult();
    private VM vm = null;
    private Program program = null;

    private InternalTransaction.TrxType trxType = TRX_UNKNOWN_TYPE;
    private InternalTransaction.ExecuterType executerType = ET_UNKNOWN_TYPE;


    /**
     * For block's trx run
     *
     * @param tx
     * @param block
     * @param deosit
     * @param programInvokeFactory
     */
    public Runtime(Transaction tx, Block block, Deposit deosit,
                   ProgramInvokeFactory programInvokeFactory) {
        this.trx = tx;
        this.block = block;
        this.deposit = deosit;
        this.programInvokeFactory = programInvokeFactory;
        this.executerType = ET_NORMAL_TYPE;

        Transaction.Contract.ContractType contractType = tx.getRawData().getContract(0).getType();
        switch (contractType.getNumber()) {
            case Transaction.Contract.ContractType.ContractCallContract_VALUE:
                trxType = TRX_CONTRACT_CALL_TYPE;
                break;
            case Transaction.Contract.ContractType.ContractCreationContract_VALUE:
                trxType = TRX_CONTRACT_CREATION_TYPE;
                break;
            default:
                trxType = TRX_PRECOMPILED_TYPE;

        }
    }

    /**
     * For pre trx run
     *
     * @param tx
     * @param deposit
     * @param programInvokeFactory
     */
    public Runtime(Transaction tx, DepositImpl deposit, ProgramInvokeFactory programInvokeFactory) {
        this.trx = tx;
        this.deposit = deposit;
        this.programInvokeFactory = programInvokeFactory;
        this.executerType = ET_PRE_TYPE;
        Transaction.Contract.ContractType contractType = tx.getRawData().getContract(0).getType();
        switch (contractType.getNumber()) {
            case Transaction.Contract.ContractType.ContractCallContract_VALUE:
                trxType = TRX_CONTRACT_CALL_TYPE;
                break;
            case Transaction.Contract.ContractType.ContractCreationContract_VALUE:
                trxType = TRX_CONTRACT_CREATION_TYPE;
                break;
            default:
                trxType = TRX_PRECOMPILED_TYPE;

        }
    }

    /**
     * For constant trx
     *
     * @param tx
     * @param programInvokeFactory
     */
    public Runtime(Transaction tx, ProgramInvokeFactory programInvokeFactory, Deposit deposit) {
        trx = tx;
        this.deposit = deposit;
        this.programInvokeFactory = programInvokeFactory;
        executerType = ET_CONSTANT_TYPE;
        trxType = TRX_CONTRACT_CALL_TYPE;

    }


    public void precompiled() {

        try {
            TransactionCapsule trxCap = new TransactionCapsule(trx);
            final List<Actuator> actuatorList = ActuatorFactory.createActuator(trxCap, deposit.getDbManager());
            TransactionResultCapsule ret = new TransactionResultCapsule();

            for (Actuator act : actuatorList) {
                act.validate();
                act.execute(ret);
                trxCap.setResult(ret);
            }
        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        } catch (Exception e) {
            program.setRuntimeFailure(new RuntimeException(e.getMessage()));
        } finally {

        }

    }

    public void execute() {
        switch (trxType) {
            case TRX_PRECOMPILED_TYPE:
                precompiled();
                break;
            case TRX_CONTRACT_CREATION_TYPE:
                create();
                break;
            case TRX_CONTRACT_CALL_TYPE:
                call();
                break;
            default:
                break;
        }
    }

    private void call() {
        Contract.ContractCallContract contract = ContractCapsule.getCallContractFromTransaction(trx);
        if (contract == null) return;

        byte[] contractAddress = contract.getContractAddress().toByteArray();
        byte[] code = this.deposit.getCode(contractAddress);
        if (isEmpty(code)) {

        } else {
            ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(TRX_CONTRACT_CALL_TYPE, executerType, trx,
                    block, deposit);
            this.vm = new VM(config);
            this.program = new Program(null, code, programInvoke,
                    new InternalTransaction(trx), config);
        }
    }

    /*
     **/
    private void create() {
        ContractCreationContract contract = ContractCapsule.getCreationContractFromTransaction(trx);

        // Create a Contract Account by ownerAddress or If the address exist, random generate one
        byte[] code = contract.getBytecode().toByteArray();
        ContractCreationContract.ABI abi = contract.getAbi();
        byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
        byte[] newContractAddress;
        if (contract.getContractAddress() == null) {
            byte[] privKey = Hash.sha256(ownerAddress);
            ECKey ecKey = ECKey.fromPrivate(privKey);
            newContractAddress = ecKey.getAddress();
            while (true) {
                AccountCapsule existingAddr = this.deposit.getAccount(newContractAddress);
                // if (existingAddr == null || existingAddr.getCodeHash().length == 0) {
                if (existingAddr == null) {
                    break;
                }

                ecKey = new ECKey(Utils.getRandom());
                newContractAddress = ecKey.getAddress();
            }
        } else {
            newContractAddress = contract.getContractAddress().toByteArray();
        }

        // crate vm to constructor smart contract
        try {
            byte[] ops = contract.getBytecode().toByteArray();
            InternalTransaction internalTransaction = new InternalTransaction(trx);
            ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(TRX_CONTRACT_CREATION_TYPE, executerType, trx,
                    block, deposit);
            this.vm = new VM(config);
            this.program = new Program(ops, programInvoke, internalTransaction, config);
        } catch(Exception e) {
            logger.error(e.getMessage());
            return;
        }

        deposit.createAccount(newContractAddress, Protocol.AccountType.Contract);
        deposit.createContract(newContractAddress, new ContractCapsule(trx));
        deposit.saveCode(newContractAddress, ProgramPrecompile.getCode(code));
    }

    public void go() {

        try {
            if (vm != null) {
                if (config.vmOn()) {
                    vm.play(program);
                }

                result = program.getResult();
                if (result.getException() != null || result.isRevert()) {
                    result.getDeleteAccounts().clear();
                    result.getLogInfoList().clear();
                    result.resetFutureRefund();

                    if (result.getException() != null) {
                        throw result.getException();
                    } else {
                        runtimeError = "REVERT opcode executed";
                    }
                } else {
                    // touchedAccounts.addAll(result.getTouchedAccounts());
                    if (executerType == ET_NORMAL_TYPE) {
                        deposit.commit();
                    }
                }

            } else {
                if (executerType == ET_NORMAL_TYPE) {
                    deposit.commit();
                }
            }
        } catch(Exception e) {
            logger.error(e.getMessage());
            runtimeError = e.getMessage();
        }
    }

    public RuntimeSummary finalization() {

        return null;
    }

    public ProgramResult getResult() {
        return result;
    }
}
