/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package groovy.swing.factory

import java.awt.Component
import java.awt.Window
import javax.swing.JSplitPane

public class SplitPaneFactory extends AbstractFactory {
    
    public Object newInstance(FactoryBuilderSupport builder, Object name, Object value, Map attributes) throws InstantiationException, IllegalAccessException {
        if (FactoryBuilderSupport.checkValueIsType(value, name, JSplitPane)) {
            return value;
        }
        JSplitPane answer = new JSplitPane();
        answer.setLeftComponent(null);
        answer.setRightComponent(null);
        answer.setTopComponent(null);
        answer.setBottomComponent(null);
        return answer;
    }

    public void setChild(FactoryBuilderSupport factory, Object parent, Object child) {
        if (!(child instanceof Component) || (child instanceof Window)) {
            return;
        }
        if (parent.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) {
            if (parent.getTopComponent() == null) {
                parent.setTopComponent(child);
            } else {
                parent.setBottomComponent(child);
            }
        } else {
            if (parent.getLeftComponent() == null) {
                parent.setLeftComponent(child);
            } else {
                parent.setRightComponent(child);
            }
        }
    }
}
