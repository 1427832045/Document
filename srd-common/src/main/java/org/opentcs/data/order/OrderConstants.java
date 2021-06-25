package org.opentcs.data.order;

/**
 * Defines some constants for {@link TransportOrder}s and {@link OrderSequence}s.
 */
public interface OrderConstants {

    /**
     * A category string indicating <em>any</em> category, primarily intended to be used for a
     * vehicle to indicate there are no restrictions to its processable categories.
     */
    String CATEGORY_ANY = "*";
    /**
     * The default category name for orders.
     */
    String CATEGORY_NONE = "-";
    /**
     * The category name for charge orders.
     */
    String CATEGORY_CHARGE = "Charge";
    /**
     * The category name for park orders.
     */
    String CATEGORY_PARK = "Park";
    /**
     * The category name for transport orders.
     */
    String CATEGORY_TRANSPORT = "Transport";
}
