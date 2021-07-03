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
package com.alibaba.csp.sentinel.context;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

/**
 * This class holds metadata of current invocation:<br/>
 *
 * <ul>
 * <li>the {@link EntranceNode}: the root of the current invocation
 * tree.</li>
 * <li>the current {@link Entry}: the current invocation point.</li>
 * <li>the current {@link Node}: the statistics related to the
 * {@link Entry}.</li>
 * <li>the origin: The origin is useful when we want to control different
 * invoker/consumer separately. Usually the origin could be the Service Consumer's app name
 * or origin IP. </li>
 * </ul>
 * <p>
 * Each {@link SphU}#entry() or {@link SphO}#entry() should be in a {@link Context},
 * if we don't invoke {@link ContextUtil}#enter() explicitly, DEFAULT context will be used.
 * </p>
 * <p>
 * A invocation tree will be created if we invoke {@link SphU}#entry() multi times in
 * the same context.
 * </p>
 * <p>
 * Same resource in different context will count separately, see {@link NodeSelectorSlot}.
 * </p>
 *
 * Context上下文Sentinel运行时一个调用链的元数据存储区。主要的元数据有：
 *
 * entranceNode：当前调用链的入口节点
 * curEntry：当前调用链的当前entry
 * node：与当前entry所对应的curNode
 * origin：当前调用链的调用源
 *
 *
 * 每次调用 SphU.entry() 或 SphO.entry()时，当框架没有找到对应的Context时，
 * 就会创建一个Context对象来存储元数据信息。当然我们可以通过收到调用的方式提前准备好Context，
 * 调用方法是：com.alibaba.csp.sentinel.context.ContextUtil#enter(java.lang.String, java.lang.String)
 *
 * Context存储在ThreadLocal中的，也就是说他是一个线程上下文。
 * 当我们执行entry完成后，需要清理这个ThreadLocal，需要执行entry.exit()方法，该方法也会进行链式调用，
 * 当发现parent==null时，也就代表执行到了最上层的节点，此时会清空Context
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see ContextUtil
 * @see NodeSelectorSlot
 */
public class Context {

    /**
     * Context name.
     * 当前上下文名称
     */
    private final String name;

    /**
     * The entrance node of current invocation tree.
     *
     * 当前调用链的入口节点
     */
    private DefaultNode entranceNode;

    /**
     * Current processing entry.
     * 当前调用链使用的 Entry
     */
    private Entry curEntry;

    /**
     * The origin of this context (usually indicate different invokers, e.g. service consumer name or origin IP).
     * 当前调用链的调用源
     */
    private String origin = "";

    /**
     * 是否异步 todo 作用？
     */
    private final boolean async;

    /**
     * Create a new async context.
     *
     * @param entranceNode entrance node of the context
     * @param name context name
     * @return the new created context
     * @since 0.2.0
     */
    public static Context newAsyncContext(DefaultNode entranceNode, String name) {
        return new Context(name, entranceNode, true);
    }

    public Context(DefaultNode entranceNode, String name) {
        this(name, entranceNode, false);
    }

    public Context(String name, DefaultNode entranceNode, boolean async) {
        this.name = name;
        this.entranceNode = entranceNode;
        this.async = async;
    }

    public boolean isAsync() {
        return async;
    }

    public String getName() {
        return name;
    }

    public Node getCurNode() {
        return curEntry == null ? null : curEntry.getCurNode();
    }

    public Context setCurNode(Node node) {
        //设置entry当前node
        this.curEntry.setCurNode(node);
        return this;
    }

    public Entry getCurEntry() {
        return curEntry;
    }

    public Context setCurEntry(Entry curEntry) {
        this.curEntry = curEntry;
        return this;
    }

    public String getOrigin() {
        return origin;
    }

    public Context setOrigin(String origin) {
        this.origin = origin;
        return this;
    }

    public double getOriginTotalQps() {
        return getOriginNode() == null ? 0 : getOriginNode().totalQps();
    }

    public double getOriginBlockQps() {
        return getOriginNode() == null ? 0 : getOriginNode().blockQps();
    }

    public double getOriginPassReqQps() {
        return getOriginNode() == null ? 0 : getOriginNode().successQps();
    }

    public double getOriginPassQps() {
        return getOriginNode() == null ? 0 : getOriginNode().passQps();
    }

    public long getOriginTotalRequest() {
        return getOriginNode() == null ? 0 : getOriginNode().totalRequest();
    }

    public long getOriginBlockRequest() {
        return getOriginNode() == null ? 0 : getOriginNode().blockRequest();
    }

    public double getOriginAvgRt() {
        return getOriginNode() == null ? 0 : getOriginNode().avgRt();
    }

    public int getOriginCurThreadNum() {
        return getOriginNode() == null ? 0 : getOriginNode().curThreadNum();
    }

    public DefaultNode getEntranceNode() {
        return entranceNode;
    }

    /**
     * Get the parent {@link Node} of the current.
     *
     * @return the parent node of the current.
     */
    public Node getLastNode() {
        if (curEntry != null && curEntry.getLastNode() != null) {
            //获取当前entry的lastNode,如果curEntry为调用链上的第一个entry则返回null
            return curEntry.getLastNode();
        } else {
            //todo 什么情况会出现curEntry==null？
            //每个Entry在创建时，都将自身设置为当前Context的curEntry了的
            //返回调用入口的node , entranceNode 与context是一一对应的
            return entranceNode;
        }
    }

    public Node getOriginNode() {
        return curEntry == null ? null : curEntry.getOriginNode();
    }

    @Override
    public String toString() {
        return "Context{" +
            "name='" + name + '\'' +
            ", entranceNode=" + entranceNode +
            ", curEntry=" + curEntry +
            ", origin='" + origin + '\'' +
            ", async=" + async +
            '}';
    }
}
