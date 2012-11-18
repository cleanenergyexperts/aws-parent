package br.com.dynamicflow.aws.s3.webcache.tag;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspWriter;

/**
 *
 * @author aoliveir
 */
public class S3CacheGetTag extends S3CacheTagSupport {
	private static final long serialVersionUID = 6607932265118064981L;
	
	private String src;
    
    @Override
    public int doStartTag() throws JspException {
        JspWriter out = pageContext.getOut();
        
        try {
            out.print(translateSrcPath(src));
        } catch (java.io.IOException ex) {
            throw new JspException("Error in S3CacheGetTag tag", ex);
        }
        return SKIP_BODY;
    }

    // called at end of tag
    public int doEndTag() {
        return EVAL_PAGE;
    }
    
    public void setSrc(String src) {
        this.src = src;
    }
}
