/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel;

import java.util.LinkedList;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.util.function.BiConsumer;

/**
 * Linked entry within current context.
 *
 * @author jialiang.linjl
 * @author Eric Zhao
 */
class CtEntry extends Entry {

    protected Entry parent = null;
    protected Entry child = null;

    protected ProcessorSlot<Object> chain;
    protected Context context;
    protected LinkedList<BiConsumer<Context, Entry>> exitHandlers;

    CtEntry(ResourceWrapper resourceWrapper, ProcessorSlot<Object> chain, Context context) {
        super(resourceWrapper);
        //设置当前entry的chain和context
        this.chain = chain;
        this.context = context;

        setUpEntryFor(context);
    }

    private void setUpEntryFor(Context context) {
        // The entry should not be associated to NullContext.
        if (context instanceof NullContext) {
            return;
        }
        //设置context的当前entry，可能是null
        /**
         * 申请entry的操作可能存在嵌套调用的情况
         * 初始时（即调用链上的第一个entry） parent==null, 若在当前 entry范围内再次申请其他entry，则
         * 则此时context.getCurEntry()不为 null，所以设置当前正在申请的 entry.parent = context.getCurEntry()
         */
        this.parent = context.getCurEntry();
        if (parent != null) {
            //形成相互引用
            ((CtEntry) parent).child = this;
        }
        //设置context的当前entry为this
        context.setCurEntry(this);
    }

    @Override
    public void exit(int count, Object... args) throws ErrorEntryFreeException {
        trueExit(count, args);
    }

    /**
     * Note: the exit handlers will be called AFTER onExit of slot chain.
     */
    private void callExitHandlersAndCleanUp(Context ctx) {
        if (exitHandlers != null && !exitHandlers.isEmpty()) {
            for (BiConsumer<Context, Entry> handler : this.exitHandlers) {
                try {
                    handler.accept(ctx, this);
                } catch (Exception e) {
                    RecordLog.warn("Error occurred when invoking entry exit handler, current entry: "
                        + resourceWrapper.getName(), e);
                }
            }
            exitHandlers = null;
        }
    }

    protected void exitForContext(Context context, int count, Object... args) throws ErrorEntryFreeException {
        if (context != null) {
            // Null context should exit without clean-up.
            if (context instanceof NullContext) {
                return;
            }

            if (context.getCurEntry() != this) {//不相等，说明出现了问题，
                // 正常情况下，每个线程进入申请一个entry时，都会设置当前entry,退出时当前entry应当与this相等
                String curEntryNameInContext = context.getCurEntry() == null ? null
                    : context.getCurEntry().getResourceWrapper().getName();
                // Clean previous call stack.
                CtEntry e = (CtEntry) context.getCurEntry();
                while (e != null) {
                    //依次退出
                    e.exit(count, args);
                    e = (CtEntry) e.parent;
                }
                String errorMessage = String.format("The order of entry exit can't be paired with the order of entry"
                        + ", current entry in context: <%s>, but expected: <%s>", curEntryNameInContext,
                    resourceWrapper.getName());
                throw new ErrorEntryFreeException(errorMessage);
            } else {
                // Go through the onExit hook of all slots.
                if (chain != null) {
                    //通过chain依次调用退出
                    chain.exit(context, resourceWrapper, count, args);
                }
                // Go through the existing terminate handlers (associated to this invocation).
                //执行退出回调
                callExitHandlersAndCleanUp(context);

                // Restore the call stack.
                //将当前entry设置回上一个
                context.setCurEntry(parent);
                if (parent != null) {
                    ((CtEntry) parent).child = null;
                }
                if (parent == null) {//parent==null,说明当前这个entry的作用范围要结束了
                    // Default context (auto entered) will be exited automatically.
                    if (ContextUtil.isDefaultContext(context)) {
                        //清理threadLocal中的context
                        ContextUtil.exit();
                    }
                }
                // Clean the reference of context in current entry to avoid duplicate exit.
                //删除entry中的context引用
                clearEntryContext();
            }
        }
    }

    protected void clearEntryContext() {
        this.context = null;
    }

    @Override
    public void whenTerminate(BiConsumer<Context, Entry> handler) {
        if (this.exitHandlers == null) {
            this.exitHandlers = new LinkedList<>();
        }
        this.exitHandlers.add(handler);
    }

    @Override
    protected Entry trueExit(int count, Object... args) throws ErrorEntryFreeException {
        exitForContext(context, count, args);

        return parent;
    }

    @Override
    public Node getLastNode() {
        return parent == null ? null : parent.getCurNode();
    }
}