/*
 * Pravega Controller APIs
 * List of admin REST APIs for the pravega controller service.
 *
 * OpenAPI spec version: 0.0.1
 *
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */



package io.pravega.controller.server.rest.generated.model;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;




/**
 * ConsumptionLimits
 */

public class ConsumptionLimits   {
  /**
   * Indicates if consumption limits is by space or time.
   */
  public enum TypeEnum {
    SIZE_MB("SIZE_MB"),
    
    TIME_MINUTES("TIME_MINUTES");

    private String value;

    TypeEnum(String value) {
      this.value = value;
    }

    @Override
    @JsonValue
    public String toString() {
      return String.valueOf(value);
    }
  }

  @JsonProperty("type")
  private TypeEnum type = null;

  @JsonProperty("max")
  private Long max = null;

  @JsonProperty("min")
  private Long min = null;

  public ConsumptionLimits type(TypeEnum type) {
    this.type = type;
    return this;
  }

   /**
   * Indicates if consumption limits is by space or time.
   * @return type
  **/
  @JsonProperty("type")
  @ApiModelProperty(value = "Indicates if consumption limits is by space or time.")
  public TypeEnum getType() {
    return type;
  }

  public void setType(TypeEnum type) {
    this.type = type;
  }

  public ConsumptionLimits max(Long max) {
    this.max = max;
    return this;
  }

   /**
   * Get max
   * @return max
  **/
  @JsonProperty("max")
  @ApiModelProperty(value = "")
  public Long getMax() {
    return max;
  }

  public void setMax(Long max) {
    this.max = max;
  }

  public ConsumptionLimits min(Long min) {
    this.min = min;
    return this;
  }

   /**
   * Get min
   * @return min
  **/
  @JsonProperty("min")
  @ApiModelProperty(value = "")
  public Long getMin() {
    return min;
  }

  public void setMin(Long min) {
    this.min = min;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ConsumptionLimits consumptionLimits = (ConsumptionLimits) o;
    return Objects.equals(this.type, consumptionLimits.type) &&
        Objects.equals(this.max, consumptionLimits.max) &&
        Objects.equals(this.min, consumptionLimits.min);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, max, min);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ConsumptionLimits {\n");
    
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    max: ").append(toIndentedString(max)).append("\n");
    sb.append("    min: ").append(toIndentedString(min)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }
}
