/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.tm.api;

import io.seata.core.exception.TransactionException;
import io.seata.core.exception.TransactionExceptionCode;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.core.model.TransactionManager;
import io.seata.tm.TransactionManagerHolder;
import io.seata.tm.api.transaction.NoRollbackRule;
import io.seata.tm.api.transaction.RollbackRule;
import io.seata.tm.api.transaction.TransactionHook;
import io.seata.tm.api.transaction.TransactionHookManager;
import io.seata.tm.api.transaction.TransactionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * @author guoyao
 */
public class TransactionTemplateTest {

    private static final String DEFAULT_XID = "123456789";
    private static final String DEFAULT_NAME = "test";
    private static final int DEFAULT_TIME_OUT = 30000;

    private TransactionalExecutor transactionalExecutor;

    @BeforeEach
    public void init() throws Exception {
        // mock transactionManager
        TransactionManager transactionManager = mock(TransactionManager.class);
        when(transactionManager.begin(null, null, DEFAULT_NAME, DEFAULT_TIME_OUT)).thenReturn(DEFAULT_XID);
        when(transactionManager.commit(DEFAULT_XID)).thenReturn(GlobalStatus.Committed);
        when(transactionManager.rollback(DEFAULT_XID)).thenReturn(GlobalStatus.Rollbacked);
        when(transactionManager.getStatus(DEFAULT_XID)).thenReturn(GlobalStatus.Begin);
        TransactionManagerHolder.set(transactionManager);

        //mock transactionalExecutor
        transactionalExecutor = Mockito.mock(TransactionalExecutor.class);
        TransactionInfo txInfo = new TransactionInfo();
        txInfo.setTimeOut(DEFAULT_TIME_OUT);
        txInfo.setName(DEFAULT_NAME);
        when(transactionalExecutor.getTransactionInfo()).thenReturn(txInfo);
    }

    @AfterEach
    public void assertHooks() {
        assertThat(TransactionHookManager.getHooks()).isEmpty();
    }

    @Test
    public void testTransactionCommitHook() throws Throwable {
        TransactionHook transactionHook = Mockito.mock(TransactionHook.class);

        TransactionHookManager.registerHook(transactionHook);
        TransactionalTemplate template = new TransactionalTemplate();
        template.execute(transactionalExecutor);
        verifyCommit(transactionHook);
    }

    @Test
    public void testTransactionRollbackHook() throws Throwable {
        TransactionHook transactionHook = Mockito.mock(TransactionHook.class);
        when(transactionalExecutor.execute()).thenThrow(new RuntimeException());
        TransactionHookManager.registerHook(transactionHook);
        TransactionalTemplate template = new TransactionalTemplate();
        try {
            template.execute(transactionalExecutor);
        } catch (Exception e) {
            //catch rollback exception
        }
        verifyRollBack(transactionHook);
    }

    @Test
    public void testTransactionRollbackHook_WithRollBackRule() throws Throwable {
        Set<RollbackRule> rollbackRules = new LinkedHashSet<>();
        rollbackRules.add(new RollbackRule(NullPointerException.class));
        TransactionHook transactionHook = testRollBackRules(rollbackRules, new NullPointerException());
        verifyRollBack(transactionHook);
    }

    @Test
    public void testTransactionRollbackHook_WithNoRollBackRule() throws Throwable {
        Set<RollbackRule> rollbackRules = new LinkedHashSet<>();
        rollbackRules.add(new NoRollbackRule(NullPointerException.class));
        TransactionHook transactionHook = testRollBackRules(rollbackRules, new NullPointerException());
        verifyCommit(transactionHook);
    }

    @Test
    public void testTransactionRollbackHook_WithSameYesNoRollBackRule() throws Throwable {
        Set<RollbackRule> rollbackRules = new LinkedHashSet<>();
        rollbackRules.add(new RollbackRule(NullPointerException.class));
        rollbackRules.add(new NoRollbackRule(NullPointerException.class));
        TransactionHook transactionHook = testRollBackRules(rollbackRules, new NullPointerException());
        verifyRollBack(transactionHook);
    }

    @Test
    public void testTransactionTemplate_rollbackTransaction() throws Throwable {
        GlobalTransaction launcherTransaction = Mockito.mock(GlobalTransaction.class);
        doNothing().when(launcherTransaction).rollback();
        when(launcherTransaction.getRole()).thenReturn(GlobalTransactionRole.Launcher);

        GlobalTransaction participantTransaction = Mockito.mock(GlobalTransaction.class);
        doNothing().when(participantTransaction).rollback();
        when(participantTransaction.getRole()).thenReturn(GlobalTransactionRole.Participant);

        TransactionalTemplate template = new TransactionalTemplate();
        Method rollbackTransaction = TransactionalTemplate.class.getDeclaredMethod("rollbackTransaction", GlobalTransaction.class, Throwable.class, boolean.class);
        rollbackTransaction.setAccessible(true);
        Method completeTransactionAfterThrowing = TransactionalTemplate.class.getDeclaredMethod("completeTransactionAfterThrowing", TransactionInfo.class, GlobalTransaction.class, Throwable.class);
        completeTransactionAfterThrowing.setAccessible(true);

        RuntimeException runtimeException = new RuntimeException("original");
        Throwable rollbackDoneException = new TransactionException(TransactionExceptionCode.ParticipantReportedRollback, runtimeException);
        Throwable rollbackFailException = new TransactionException("Failed to report global rollback", runtimeException);

        TransactionInfo transactionInfo = new TransactionInfo();
        transactionInfo.setParticipantReportRollback(true);

        //When Participant RuntimeException occurred, rollback and throw original Exception
        try {
            rollbackTransaction.invoke(template, participantTransaction, runtimeException, transactionInfo.getParticipantReportRollback());
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.ParticipantRollbackDone);
            Assertions.assertEquals(executionException.getOriginalException().getMessage(), runtimeException.getMessage());
        }

        //When Participant received rollbackDoneException, rollback and throw original Exception
        try {
            rollbackTransaction.invoke(template, participantTransaction, rollbackDoneException, transactionInfo.getParticipantReportRollback());
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.ParticipantRollbackDone);
        }

        //When Launcher RuntimeException occurred, rollback and throw original Exception
        try {
            rollbackTransaction.invoke(template, launcherTransaction, runtimeException, transactionInfo.getParticipantReportRollback());
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.RollbackDone);
            Assertions.assertEquals(executionException.getOriginalException().getMessage(), runtimeException.getMessage());
        }

        //When Launcher received rollbackDoneException ,rollback and throw original Exception
        try {
            rollbackTransaction.invoke(template, launcherTransaction, rollbackDoneException, transactionInfo.getParticipantReportRollback());
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.RollbackDone);
        }

        //When Launcher RuntimeException occurred, rollbackFailure and throw cause
        try {
            doThrow(rollbackFailException).when(launcherTransaction).rollback();
            completeTransactionAfterThrowing.invoke(template, new TransactionInfo(), launcherTransaction, runtimeException);
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.RollbackFailure);
            Assertions.assertEquals(executionException.getCause(),rollbackFailException);
        }

        //When Participant RuntimeException occurred, rollbackFailure and throw cause
        try {
            doThrow(rollbackFailException).when(participantTransaction).rollback();
            TransactionInfo participantReportTxInfo = new TransactionInfo();
            participantReportTxInfo.setParticipantReportRollback(true);
            completeTransactionAfterThrowing.invoke(template, participantReportTxInfo, participantTransaction, runtimeException);
        }catch (InvocationTargetException e) {
            TransactionalExecutor.ExecutionException executionException = ((TransactionalExecutor.ExecutionException) e.getTargetException());
            Assertions.assertEquals(executionException.getCode(), TransactionalExecutor.Code.ParticipantRollbackFailure);
            Assertions.assertEquals(executionException.getCause(),rollbackFailException);
        }
    }

    private TransactionHook testRollBackRules(Set<RollbackRule> rollbackRules, Throwable throwable) throws Throwable {
        TransactionHook transactionHook = Mockito.mock(TransactionHook.class);
        // mock  txInfo
        TransactionInfo txInfo = new TransactionInfo();
        txInfo.setTimeOut(DEFAULT_TIME_OUT);
        txInfo.setName(DEFAULT_NAME);
        txInfo.setRollbackRules(rollbackRules);
        when(transactionalExecutor.getTransactionInfo()).thenReturn(txInfo);

        when(transactionalExecutor.execute()).thenThrow(throwable);
        TransactionHookManager.registerHook(transactionHook);
        TransactionalTemplate template = new TransactionalTemplate();
        try {
            template.execute(transactionalExecutor);
        } catch (Exception e) {
            //catch rollback exception
        }
        return transactionHook;
    }

    private void verifyCommit(TransactionHook transactionHook) {
        verify(transactionHook).beforeBegin();
        verify(transactionHook).afterBegin();
        verify(transactionHook).beforeCommit();
        verify(transactionHook).afterCommit();
        verify(transactionHook).afterCompletion();
    }

    private void verifyRollBack(TransactionHook transactionHook) {
        verify(transactionHook).beforeBegin();
        verify(transactionHook).afterBegin();
        verify(transactionHook).beforeRollback();
        verify(transactionHook).afterRollback();
        verify(transactionHook).afterCompletion();
    }

    @Test
    public void testExistingTransaction(){
        RootContext.bind(DEFAULT_XID);
        TransactionalTemplate template = new TransactionalTemplate();
        Assertions.assertTrue(template.existingTransaction(),"Existing transaction");

        RootContext.unbind();
        Assertions.assertFalse(template.existingTransaction(),"No existing transaction");
    }
}
