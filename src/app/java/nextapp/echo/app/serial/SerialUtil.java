package nextapp.echo.app.serial;

import org.w3c.dom.Element;
import nextapp.echo.app.util.Context;

public class SerialUtil {
    
    //FIXME. this class needs a better name.

    public static void toXml(Context context, Class objectClass, Element parentElement, String propertyName, 
            Object propertyValue) {
        if (propertyValue != null) {
            SerialContext serialContext = (SerialContext) context.get(SerialContext.class);
            Element childPropertyElement = serialContext.getDocument().createElement("p");
            childPropertyElement.setAttribute("n", propertyName);
            
            PropertyPeerFactory peerFactory = (PropertyPeerFactory) context.get(PropertyPeerFactory.class);
            
            peerFactory.getPeerForProperty(propertyValue.getClass())
                    .toXml(context, objectClass, childPropertyElement, propertyValue);
            parentElement.appendChild(childPropertyElement);
        }
    }
}
