/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.policy.js.autobind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.candlepin.model.Consumer;
import org.candlepin.model.Pool;
import org.candlepin.model.PoolQuantity;
import org.candlepin.model.Product;
import org.candlepin.model.ProvidedProduct;
import org.candlepin.policy.js.ArgumentJsContext;
import org.candlepin.policy.js.JsContext;
import org.candlepin.policy.js.JsRunner;
import org.candlepin.policy.js.ProductCache;
import org.candlepin.policy.js.ReadOnlyConsumer;
import org.candlepin.policy.js.ReadOnlyPool;
import org.candlepin.policy.js.ReadOnlyProduct;
import org.candlepin.policy.js.RuleExecutionException;
import org.candlepin.policy.js.compliance.ComplianceStatus;
import org.candlepin.util.X509ExtensionUtil;
import org.mozilla.javascript.RhinoException;

import com.google.inject.Inject;

/**
 * AutobindRules
 *
 * Defers to rules to determine the best match of pools for a given consumer.
 */
public class AutobindRules {

    protected static final String SELECT_POOL_FUNCTION = "select_pools";
    protected static final String PROD_ARCHITECTURE_SEPARATOR = ",";

    private JsRunner jsRules;
    private static Logger log = Logger.getLogger(AutobindRules.class);
    private Logger rulesLogger = Logger.getLogger(
        AutobindRules.class.getCanonicalName() + ".rules");
    private ProductCache productCache;


    @Inject
    public AutobindRules(JsRunner jsRules, ProductCache productCache) {
        this.jsRules = jsRules;
        this.productCache = productCache;

        jsRules.init("autobind_name_space");
    }

    public List<PoolQuantity> selectBestPools(Consumer consumer, String[] productIds,
        List<Pool> pools, ComplianceStatus compliance, String serviceLevelOverride,
        Set<String> exemptLevels) {

        int poolsBeforeContentFilter = pools.size();
        pools = filterPoolsForV1Certificates(consumer, pools);

        // TODO: Not the best behavior:
        if (pools.size() == 0) {
            throw new RuntimeException("No entitlements for products: " +
                Arrays.toString(productIds));
        }

        if (log.isDebugEnabled()) {
            log.debug("Selecting best entitlement pool for products: " +
                Arrays.toString(productIds));
            if (poolsBeforeContentFilter != pools.size()) {
                log.debug((poolsBeforeContentFilter - pools.size()) + " pools filtered " +
                    "due to too much content");
            }
        }
        List<ReadOnlyPool> readOnlyPools = ReadOnlyPool.fromCollection(pools);

        /*
         * NOTE: These are engineering product IDs being passed in which are installed on
         * the given system. There is almost no value to looking these up from the product
         * service as there's not much useful, and indeed all the select pool rules ever
         * use is the product ID, which we had before we did the lookup. Unfortunately we
         * need to maintain backward compatability with past rules files, so we will
         * continue providing ReadOnlyProduct objects to the rules, but we'll just
         * pre-populate the ID field and not do an actual lookup.
         */
        List<ReadOnlyProduct> readOnlyProducts = new LinkedList<ReadOnlyProduct>();
        for (String productId : productIds) {
            // NOTE: using ID as name here, rules just need ID:
            ReadOnlyProduct roProduct = new ReadOnlyProduct(productId, productId,
                new HashMap<String, String>());
            readOnlyProducts.add(roProduct);
        }

        // Provide objects for the script:
        ArgumentJsContext args = new ArgumentJsContext();
        args.put("consumer", new ReadOnlyConsumer(consumer, serviceLevelOverride));
        args.put("pools", readOnlyPools.toArray());
        args.put("products", readOnlyProducts.toArray());
        args.put("prodAttrSeparator", PROD_ARCHITECTURE_SEPARATOR);
        args.put("log", rulesLogger);
        args.put("compliance", compliance);
        args.put("exemptList", exemptLevels);

        Map<ReadOnlyPool, Integer> result = null;
        try {
            Object output =
                jsRules.invokeMethod(SELECT_POOL_FUNCTION, args);
            result = jsRules.convertMap(output);
            if (log.isDebugEnabled()) {
                log.debug("Excuted javascript rule: " + SELECT_POOL_FUNCTION);
            }
        }
        catch (NoSuchMethodException e) {
            log.warn("No method found: " + SELECT_POOL_FUNCTION);
            log.warn("Resorting to default pool selection behavior.");
            return selectBestPoolDefault(pools);
        }
        catch (RhinoException e) {
            throw new RuleExecutionException(e);
        }

        if (pools.size() > 0 && result == null) {
            throw new RuleExecutionException(
                "Rule did not select a pool for products: " + Arrays.toString(productIds));
        }

        List<PoolQuantity> bestPools = new ArrayList<PoolQuantity>();
        for (Pool p : pools) {
            for (Entry<ReadOnlyPool, Integer> entry : result.entrySet()) {
                if (p.getId().equals(entry.getKey().getId())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Best pool: " + p);
                    }

                    int quantity = entry.getValue();
                    bestPools.add(new PoolQuantity(p, quantity));
                }
            }
        }

        if (bestPools.size() > 0) {
            return bestPools;
        }
        else {
            return null;
        }
    }

    /**
     * Default behavior if no product specific and no global pool select rules
     * exist.
     *
     * @param pools Pools to choose from.
     * @return First pool in the list. (default behavior)
     */
    protected List<PoolQuantity> selectBestPoolDefault(List<Pool> pools) {
        if (pools.size() > 0) {
            List<PoolQuantity> toReturn = new ArrayList<PoolQuantity>();
            for (Pool pool : pools) {
                toReturn.add(new PoolQuantity(pool, 1));
            }
            return toReturn;
        }

        return null;
    }

    /*
     * If this consumer only supports V1 certificates, we need to filter out pools
     * with too many content sets.
     */
    private List<Pool> filterPoolsForV1Certificates(Consumer consumer,
        List<Pool> pools) {
        if (!consumer.hasFact("system.certificate_version") ||
            (consumer.hasFact("system.certificate_version") &&
            consumer.getFact("system.certificate_version").startsWith("1."))) {
            List<Pool> newPools = new LinkedList<Pool>();

            for (Pool p : pools) {
                boolean contentOk = true;

                // Check each provided product, if *any* have too much content, we must
                // skip the pool:
                for (ProvidedProduct providedProd : p.getProvidedProducts()) {
                    Product product = productCache.getProductById(
                        providedProd.getProductId());
                    if (product.getProductContent().size() >
                        X509ExtensionUtil.V1_CONTENT_LIMIT) {
                        contentOk = false;
                        break;
                    }
                }
                if (contentOk) {
                    newPools.add(p);
                }
            }
            return newPools;
        }

        // Otherwise return the list of pools as is:
        return pools;
    }

    private <T extends Object> T runJsFunction(Class<T> clazz, String function,
        JsContext context) {
        T returner = null;
        try {
            returner = jsRules.invokeMethod(function, context);
        }
        catch (NoSuchMethodException e) {
            log.warn("No compliance javascript method found: " + function);
        }
        catch (RhinoException e) {
            throw new RuleExecutionException(e);
        }
        return returner;
    }

}
