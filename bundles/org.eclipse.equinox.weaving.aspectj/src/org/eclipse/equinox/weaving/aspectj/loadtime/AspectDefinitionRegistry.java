
package org.eclipse.equinox.weaving.aspectj.loadtime;

import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.aspectj.weaver.loadtime.definition.Definition;
import org.aspectj.weaver.loadtime.definition.DocumentParser;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;

public class AspectDefinitionRegistry implements SynchronousBundleListener {

    private static final String AOP_CONTEXT_LOCATION_HEADER = "Eclipse-AspectContext"; //$NON-NLS-1$

    private static final String DEFAULT_AOP_CONTEXT_LOCATION = "META-INF/aop.xml"; //$NON-NLS-1$

    private final Map<Bundle, Definition> aspectDefinitions;

    /**
     * Create a registry to manage aspect definition files
     */
    public AspectDefinitionRegistry() {
        this.aspectDefinitions = new ConcurrentHashMap<Bundle, Definition>();
    }

    /**
     * @see org.osgi.framework.BundleListener#bundleChanged(org.osgi.framework.BundleEvent)
     */
    public void bundleChanged(final BundleEvent event) {
        if (event.getType() == BundleEvent.RESOLVED) {
            bundleResolved(event.getBundle());
        } else if (event.getType() == BundleEvent.UNRESOLVED) {
            bundleUnresolved(event.getBundle());
        }
    }

    public void bundleResolved(final Bundle bundle) {
        if (!this.aspectDefinitions.containsKey(bundle)) {
            this.aspectDefinitions.put(bundle,
                    parseDefinitionFromRequiredBundle(bundle));
        }
    }

    public void bundleUnresolved(final Bundle bundle) {
        this.aspectDefinitions.remove(bundle);
    }

    public Definition getAspectDefinition(final Bundle bundle) {
        return this.aspectDefinitions.get(bundle);
    }

    public String getDefinitionLocation(final Bundle bundle) {
        String aopContextHeader = (String) bundle.getHeaders().get(
                AOP_CONTEXT_LOCATION_HEADER);
        if (aopContextHeader != null) {
            aopContextHeader = aopContextHeader.trim();
            return aopContextHeader;
        }

        return DEFAULT_AOP_CONTEXT_LOCATION;
    }

    public void initialize(final Bundle[] bundles) {
        for (final Bundle bundle : bundles) {
            final int state = bundle.getState();
            if (state != Bundle.INSTALLED && state != Bundle.UNINSTALLED) {
                final Definition aspectDefinitions = parseDefinitionFromRequiredBundle(bundle);
                if (aspectDefinitions != null) {
                    this.aspectDefinitions.put(bundle, aspectDefinitions);
                }
            }
        }
    }

    public Definition parseDefinitionFromRequiredBundle(final Bundle bundle) {
        try {
            final URL aopXmlDef = bundle
                    .getEntry(getDefinitionLocation(bundle));
            if (aopXmlDef != null) {
                return DocumentParser.parse(aopXmlDef);
            }
        } catch (final Exception e) {
            //            warn("parse definitions failed", e);
        }
        return null;
    }

}
