import org.hibernate.validator.constraints.URL;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.Validation;
import java.util.Set;

public class test_ftp {
    public static void main(String[] args) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        
        // Test FTP
        Set<ConstraintViolation<String>> violations = validator.validate("ftp://example.com/file.txt");
        System.out.println("FTP violations: " + violations.size());
        
        // Test invalid URL
        violations = validator.validate("not a url");
        System.out.println("Invalid violations: " + violations.size());
    }
}
