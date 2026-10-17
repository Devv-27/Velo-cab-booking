package com.cab;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice
public class Errs {
  static ResponseEntity<Map<String,String>> r(int c,String m){ return ResponseEntity.status(c).body(Map.of("error",m)); }
  @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> a(ResponseStatusException e){ return r(e.getStatusCode().value(),e.getReason()==null?"Error":e.getReason()); }
  @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<Map<String,String>> b(AccessDeniedException e){ return r(403,"Forbidden"); }
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class) public ResponseEntity<Map<String,String>> c(Exception e){ return r(409,"Ride changed, please retry"); }
  @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<Map<String,String>> d(Exception e){ return r(409,"Conflict"); }
  @ExceptionHandler(HttpMessageNotReadableException.class) public ResponseEntity<Map<String,String>> e(Exception e){ return r(400,"Bad request"); }
  @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> f(Exception e){
    if(e instanceof org.springframework.web.ErrorResponse er) return r(er.getStatusCode().value(),"Request failed");   // 404, 405, 415 stay as they are
    return r(500,"Server error"); }
}
