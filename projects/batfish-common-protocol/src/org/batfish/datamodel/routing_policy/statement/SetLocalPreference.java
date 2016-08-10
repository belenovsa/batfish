package org.batfish.datamodel.routing_policy.statement;

import org.batfish.datamodel.Route;
import org.batfish.datamodel.routing_policy.Environment;
import org.batfish.datamodel.routing_policy.Result;

import com.fasterxml.jackson.annotation.JsonCreator;

public class SetLocalPreference extends AbstractStatement {

   /**
    *
    */
   private static final long serialVersionUID = 1L;
   private int _localPreference;

   @JsonCreator
   public SetLocalPreference() {
   }

   public SetLocalPreference(int localPreference) {
      _localPreference = localPreference;
   }

   @Override
   public Result execute(Environment environment, Route route) {
      Result result = new Result();
      result.setReturn(false);
      return result;
   }

   public int getLocalPreference() {
      return _localPreference;
   }

   public void setLocalPreference(int localPreference) {
      _localPreference = localPreference;
   }

}
