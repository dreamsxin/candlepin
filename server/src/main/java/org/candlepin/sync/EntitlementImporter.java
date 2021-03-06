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
package org.candlepin.sync;

import org.candlepin.dto.manifest.v1.BrandingDTO;
import org.candlepin.dto.manifest.v1.CertificateDTO;
import org.candlepin.dto.manifest.v1.CertificateSerialDTO;
import org.candlepin.dto.manifest.v1.EntitlementDTO;
import org.candlepin.dto.manifest.v1.PoolDTO;
import org.candlepin.model.Branding;
import org.candlepin.model.Cdn;
import org.candlepin.model.CdnCurator;
import org.candlepin.model.CertificateSerial;
import org.candlepin.model.CertificateSerialCurator;
import org.candlepin.model.Entitlement;
import org.candlepin.model.EntitlementCertificate;
import org.candlepin.model.Owner;
import org.candlepin.model.Pool;
import org.candlepin.model.Product;
import org.candlepin.model.ProductCurator;
import org.candlepin.model.ProvidedProduct;
import org.candlepin.model.SubscriptionsCertificate;
import org.candlepin.model.dto.ProductData;
import org.candlepin.model.dto.Subscription;
import org.candlepin.util.Util;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnap.commons.i18n.I18n;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * EntitlementImporter - turn an upstream Entitlement into a local subscription
 */
public class EntitlementImporter {
    private static Logger log = LoggerFactory.getLogger(EntitlementImporter.class);

    private CertificateSerialCurator csCurator;
    private CdnCurator cdnCurator;
    private I18n i18n;
    private ProductCurator productCurator;

    public EntitlementImporter(CertificateSerialCurator csCurator,
        CdnCurator cdnCurator, I18n i18n, ProductCurator productCurator) {

        this.csCurator = csCurator;
        this.cdnCurator = cdnCurator;
        this.i18n = i18n;
        this.productCurator = productCurator;
    }

    public Subscription importObject(ObjectMapper mapper, Reader reader, Owner owner,
        Map<String, Product> productsById, String consumerUuid, Meta meta)
        throws IOException, SyncDataFormatException {

        EntitlementDTO entitlementDTO = mapper.readValue(reader, EntitlementDTO.class);
        Entitlement entitlement = new Entitlement();
        populateEntity(entitlement, entitlementDTO);

        Subscription subscription = new Subscription();

        log.debug("Building subscription for owner: {}", owner);
        log.debug("Using pool from entitlement: {}", entitlement.getPool());

        // Now that we no longer store Subscriptions in the on-site database, we need to
        // manually give the subscription a downstream ID. Note that this may later be
        // overwritten by reconciliation code if it determines this Subscription
        // should replace and existing one.
        subscription.setId(Util.generateDbUUID());

        subscription.setUpstreamPoolId(entitlement.getPool().getId());
        subscription.setUpstreamEntitlementId(entitlement.getId());
        subscription.setUpstreamConsumerId(consumerUuid);

        subscription.setOwner(owner);

        subscription.setStartDate(entitlement.getStartDate());
        subscription.setEndDate(entitlement.getEndDate());

        subscription.setAccountNumber(entitlement.getPool().getAccountNumber());
        subscription.setContractNumber(entitlement.getPool().getContractNumber());
        subscription.setOrderNumber(entitlement.getPool().getOrderNumber());

        subscription.setQuantity(entitlement.getQuantity().longValue());

        for (Branding b : entitlement.getPool().getBranding()) {
            subscription.getBranding().add(new Branding(b.getProductId(), b.getType(), b.getName()));
        }

        String cdnLabel = meta.getCdnLabel();
        if (!StringUtils.isBlank(cdnLabel)) {
            Cdn cdn = cdnCurator.lookupByLabel(cdnLabel);
            if (cdn != null) {
                subscription.setCdn(cdn);
            }
        }

        Product product = this.findProduct(productsById, entitlement.getPool().getProductId());
        subscription.setProduct(product.toDTO());

        // Add any sub product data to the subscription.
        if (entitlement.getPool().getDerivedProductId() != null) {
            product = this.findProduct(productsById, entitlement.getPool().getDerivedProductId());
            subscription.setDerivedProduct(product.toDTO());
        }

        associateProvidedProducts(productsById, entitlement, subscription);

        Set<EntitlementCertificate> certs = entitlement.getCertificates();

        // subscriptions have one cert
        int entcnt = 0;
        for (EntitlementCertificate cert : certs) {
            ++entcnt;

            CertificateSerial cs = new CertificateSerial();
            cs.setCollected(cert.getSerial().isCollected());
            cs.setExpiration(cert.getSerial().getExpiration());
            cs.setUpdated(cert.getSerial().getUpdated());
            cs.setCreated(cert.getSerial().getCreated());

            SubscriptionsCertificate sc = new SubscriptionsCertificate();
            sc.setKey(cert.getKey());
            sc.setCertAsBytes(cert.getCertAsBytes());
            sc.setSerial(cs);

            subscription.setCertificate(sc);
        }

        if (entcnt > 1) {
            log.error("More than one entitlement cert found for subscription");
        }

        return subscription;
    }


    /**
     * Populates the specified entity with data from the provided DTO.
     *
     * @param entity
     *  The entity instance to populate
     *
     * @param dto
     *  The DTO containing the data with which to populate the entity
     *
     * @throws IllegalArgumentException
     *  if either entity or dto are null
     */
    @SuppressWarnings("checkstyle:methodlength")
    private void populateEntity(Entitlement entity, EntitlementDTO dto) {
        if (entity == null) {
            throw new IllegalArgumentException("the entitlement model entity is null");
        }

        if (dto == null) {
            throw new IllegalArgumentException("the entitlement dto is null");
        }

        if (dto.getId() != null) {
            entity.setId(dto.getId());
        }

        if (dto.getQuantity() != null) {
            entity.setQuantity(dto.getQuantity());
        }

        if (dto.getPool() != null) {
            PoolDTO poolDTO = dto.getPool();
            Pool poolEntity = new Pool();

            if (poolDTO.getId() != null) {
                poolEntity.setId(poolDTO.getId());
            }

            if (poolDTO.getProductId() != null) {
                poolEntity.setProductId(poolDTO.getProductId());
            }

            if (poolDTO.getDerivedProductId() != null) {
                poolEntity.setDerivedProductId(poolDTO.getDerivedProductId());
            }

            if (poolDTO.getStartDate() != null) {
                poolEntity.setStartDate(poolDTO.getStartDate());
            }

            if (poolDTO.getEndDate() != null) {
                poolEntity.setEndDate(poolDTO.getEndDate());
            }

            if (poolDTO.getAccountNumber() != null) {
                poolEntity.setAccountNumber(poolDTO.getAccountNumber());
            }

            if (poolDTO.getOrderNumber() != null) {
                poolEntity.setOrderNumber(poolDTO.getOrderNumber());
            }

            if (poolDTO.getContractNumber() != null) {
                poolEntity.setContractNumber(poolDTO.getContractNumber());
            }

            if (poolDTO.getBranding() != null) {
                if (poolDTO.getBranding().isEmpty()) {
                    poolEntity.setBranding(Collections.emptySet());
                }
                else {
                    Set<Branding> branding = new HashSet<>();
                    for (BrandingDTO brandingDTO : poolDTO.getBranding()) {
                        if (brandingDTO != null) {
                            branding.add(new Branding(
                                brandingDTO.getProductId(),
                                brandingDTO.getType(),
                                brandingDTO.getName()));
                        }
                    }
                    poolEntity.setBranding(branding);
                }
            }

            if (poolDTO.getProvidedProducts() != null) {
                if (poolDTO.getProvidedProducts().isEmpty()) {
                    poolEntity.setProvidedProductDtos(Collections.emptySet());
                }
                else {
                    Set<ProvidedProduct> providedProducts = new HashSet<>();
                    for (PoolDTO.ProvidedProductDTO ppDTO : poolDTO.getProvidedProducts()) {
                        if (ppDTO != null) {
                            ProvidedProduct providedProduct = new ProvidedProduct();
                            providedProduct.setProductId(ppDTO.getProductId());
                            providedProduct.setProductName(ppDTO.getProductName());
                            providedProducts.add(providedProduct);
                        }
                    }
                    poolEntity.setProvidedProductDtos(providedProducts);
                }
            }

            if (poolDTO.getDerivedProvidedProducts() != null) {
                if (poolDTO.getDerivedProvidedProducts().isEmpty()) {
                    poolEntity.setDerivedProvidedProductDtos(Collections.emptySet());
                }
                else {
                    Set<ProvidedProduct> derivedProvidedProducts = new HashSet<>();
                    for (PoolDTO.ProvidedProductDTO dppDTO : poolDTO.getDerivedProvidedProducts()) {
                        if (dppDTO != null) {
                            ProvidedProduct derivedProvidedProduct = new ProvidedProduct();
                            derivedProvidedProduct.setProductId(dppDTO.getProductId());
                            derivedProvidedProduct.setProductName(dppDTO.getProductName());
                            derivedProvidedProducts.add(derivedProvidedProduct);
                        }
                    }
                    poolEntity.setDerivedProvidedProductDtos(derivedProvidedProducts);
                }
            }

            entity.setPool(poolEntity);
        }

        if (dto.getCertificates() != null) {
            if (dto.getCertificates().isEmpty()) {
                entity.setCertificates(Collections.emptySet());
            }
            else {
                Set<EntitlementCertificate> entityCerts = new HashSet<>();
                for (CertificateDTO dtoCert : dto.getCertificates()) {
                    if (dtoCert != null) {
                        EntitlementCertificate entityCert = new EntitlementCertificate();
                        entityCert.setId(dtoCert.getId());
                        entityCert.setKey(dtoCert.getKey());
                        entityCert.setCert(dtoCert.getCert());

                        if (dtoCert.getSerial() != null) {
                            CertificateSerialDTO dtoSerial = dtoCert.getSerial();
                            CertificateSerial entitySerial = new CertificateSerial();
                            entitySerial.setId(dtoSerial.getId());
                            entitySerial.setCollected(dtoSerial.isCollected());
                            entitySerial.setExpiration(dtoSerial.getExpiration());
                            entitySerial.setCreated(dtoSerial.getCreated());
                            entitySerial.setUpdated(dtoSerial.getUpdated());

                            entityCert.setSerial(entitySerial);
                        }
                        entityCerts.add(entityCert);
                    }
                }
                entity.setCertificates(entityCerts);
            }
        }
    }

    /*
     * Transfer associations to provided and derived provided products over to the
     * subscription.
     *
     * WARNING: This is a bit tricky due to backward compatibility issues. Prior to
     * candlepin 2.0, pools serialized a collection of disjoint provided product info,
     * an object with just a product ID and name, not directly linked to anything in the db.
     *
     * In candlepin 2.0 we have referential integrity and links to full product objects,
     * but we need to maintain both API and import compatibility with old manifests and
     * servers that may import new manifests.
     *
     * To do this, we serialize the providedProductDtos and derivedProvidedProductDtos
     * collections on pool which keeps the API/manifest JSON identical to what it was
     * before. On import we load into these transient collections, and here we transfer
     * to the actual persisted location.
     */
    public void associateProvidedProducts(Map<String, Product> productsById, Entitlement entitlement,
        Subscription subscription)
        throws SyncDataFormatException {

        // Associate main provided products:
        Set<ProductData> providedProducts = new HashSet<>();
        entitlement.getPool().populateAllTransientProvidedProducts(productCurator);
        for (ProvidedProduct providedProduct : entitlement.getPool().getProvidedProductDtos()) {
            Product product = this.findProduct(productsById, providedProduct.getProductId());
            providedProducts.add(product.toDTO());
        }
        subscription.setProvidedProducts(providedProducts);

        // Associate derived provided products:
        Set<ProductData> derivedProvidedProducts = new HashSet<>();
        for (ProvidedProduct pp : entitlement.getPool().getDerivedProvidedProductDtos()) {
            Product product = this.findProduct(productsById, pp.getProductId());
            derivedProvidedProducts.add(product.toDTO());
        }
        subscription.setDerivedProvidedProducts(derivedProvidedProducts);

        log.debug("Subscription has {} provided products.", derivedProvidedProducts.size());
        log.debug("Subscription has {} derived provided products.", derivedProvidedProducts.size());
    }

    private Product findProduct(Map<String, Product> productsById, String productId)
        throws SyncDataFormatException {

        Product product = productsById.get(productId);
        if (product == null) {
            throw new SyncDataFormatException(i18n.tr("Unable to find product with ID: {0}", productId));
        }

        return product;
    }

}
