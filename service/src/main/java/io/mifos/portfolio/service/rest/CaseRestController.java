/*
 * Copyright 2017 The Mifos Initiative.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.portfolio.service.rest;

import io.mifos.anubis.annotation.AcceptedTokenType;
import io.mifos.anubis.annotation.Permittable;
import io.mifos.portfolio.api.v1.PermittableGroupIds;
import io.mifos.portfolio.api.v1.domain.Case;
import io.mifos.portfolio.api.v1.domain.Command;
import io.mifos.products.spi.ProductCommandDispatcher;
import io.mifos.portfolio.service.internal.command.ChangeCaseCommand;
import io.mifos.portfolio.service.internal.command.CreateCaseCommand;
import io.mifos.portfolio.service.internal.service.CaseService;
import io.mifos.portfolio.service.internal.service.ProductService;
import io.mifos.core.api.util.UserContextHolder;
import io.mifos.core.command.gateway.CommandGateway;
import io.mifos.core.lang.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Set;

/**
 * @author Myrle Krantz
 */
@SuppressWarnings("unused")
@RestController
@RequestMapping("/products/{productidentifier}/cases/")
public class CaseRestController {

  private final CommandGateway commandGateway;
  private final CaseService caseService;
  private final ProductService productService;

  @Autowired public CaseRestController(
          final CommandGateway commandGateway,
          final CaseService caseService,
          final ProductService productService) {
    super();
    this.commandGateway = commandGateway;
    this.caseService = caseService;
    this.productService = productService;
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          method = RequestMethod.GET,
          consumes = MediaType.ALL_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public @ResponseBody List<Case> getAllCasesForProduct(
          @PathVariable("productidentifier") final String productIdentifier,
          @RequestParam(value = "includeClosed", required = false) final Boolean includeClosed,
          @RequestParam(value = "forCustomer", required = false) final String customerIdentifier,
          @RequestParam("page") final Integer page,
          @RequestParam("size") final Integer size)
  {
    //TODO: paging, parameters
    return caseService.findAllEntities();
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          method = RequestMethod.POST,
          consumes = MediaType.APPLICATION_JSON_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public @ResponseBody ResponseEntity<Void> createCase(
          @PathVariable("productidentifier") final String productIdentifier,
          @RequestBody @Valid final Case instance)
  {
    checkThatProductExists(productIdentifier);

    caseService.findByIdentifier(productIdentifier, instance.getIdentifier())
            .ifPresent(x -> {throw ServiceException.conflict("Duplicate identifier: " + productIdentifier + "." + x.getIdentifier());});

    if (!instance.getProductIdentifier().equals(productIdentifier))
      throw ServiceException.badRequest("Product identifier in request body must match product identifier in request path.");

    if (!(instance.getCurrentState() == null || instance.getCurrentState().equals(Case.State.CREATED.name())))
      throw ServiceException.badRequest("Current state must be either 'null', or CREATED upon initial creation.");

    final String user = UserContextHolder.checkedGetUser();
    if (!(instance.getCreatedBy() == null || instance.getCreatedBy().equals(user)))
      throw ServiceException.badRequest("CreatedBy must be either 'null', or the creating user upon initial creation.");

    if (!(instance.getLastModifiedBy() == null || instance.getLastModifiedBy().equals(user)))
      throw ServiceException.badRequest("LastModifiedBy must be either 'null', or the creating user upon initial creation.");

    if (instance.getCreatedOn() != null)
      throw ServiceException.badRequest("CreatedOn must be 'null' upon initial creation.");

    if (instance.getLastModifiedOn() != null)
      throw ServiceException.badRequest("LastModifiedOn must 'null' be upon initial creation.");

    //TODO: validate that all designators actually exist in product/pattern...

    this.commandGateway.process(new CreateCaseCommand(instance));
    return new ResponseEntity<>(HttpStatus.ACCEPTED);
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          value = "{caseidentifier}",
          method = RequestMethod.GET,
          consumes = MediaType.ALL_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE)
  public @ResponseBody Case getCase(
          @PathVariable("productidentifier") final String productIdentifier,
          @PathVariable("caseidentifier") final String caseIdentifier)
  {
    return caseService.findByIdentifier(productIdentifier, caseIdentifier)
            .orElseThrow(() -> ServiceException.notFound(
                    "Instance with identifier " + productIdentifier + "." + caseIdentifier + " doesn't exist."));
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          value = "{caseidentifier}",
          method = RequestMethod.PUT,
          consumes = MediaType.APPLICATION_JSON_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public @ResponseBody ResponseEntity<Void> changeCase(
          @PathVariable("productidentifier") final String productIdentifier,
          @PathVariable("caseidentifier") final String caseIdentifier,
          @RequestBody @Valid final Case instance)
  {
    checkThatCaseExists(productIdentifier, caseIdentifier);

    if (!productIdentifier.equals(instance.getProductIdentifier()))
      throw ServiceException.badRequest("Product reference may not be changed.");

    if (!caseIdentifier.equals(instance.getIdentifier()))
      throw ServiceException.badRequest("Instance identifier may not be changed.");

    this.commandGateway.process(new ChangeCaseCommand(instance));
    return new ResponseEntity<>(HttpStatus.ACCEPTED);
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          value = "{caseidentifier}/actions/",
          method = RequestMethod.GET,
          consumes = MediaType.ALL_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  @ResponseBody
  Set<String> getActionsForCase(@PathVariable("productidentifier") final String productIdentifier,
                                @PathVariable("caseidentifier") final String caseIdentifier)
  {
    checkThatCaseExists(productIdentifier, caseIdentifier);

    return caseService.getNextActionsForCase(productIdentifier, caseIdentifier);
  }

  @Permittable(value = AcceptedTokenType.TENANT, groupId = PermittableGroupIds.CASE_MANAGEMENT)
  @RequestMapping(
          value = "{caseidentifier}/commands/",
          method = RequestMethod.POST,
          produces = MediaType.APPLICATION_JSON_VALUE,
          consumes = MediaType.APPLICATION_JSON_VALUE
  )
  public @ResponseBody ResponseEntity<Void> executeCaseCommand(@PathVariable("productidentifier") final String productIdentifier,
                                                               @PathVariable("caseidentifier") final String caseIdentifier,
                                                               @RequestBody @Valid final Command command)
  {
    checkThatCaseExists(productIdentifier, caseIdentifier);
    final Set<String> nextActions = caseService.getNextActionsForCase(productIdentifier, caseIdentifier);
    if (!nextActions.contains(command.getAction()))
      throw ServiceException.badRequest("Action " + command.getAction() + " cannot be taken from current state.");

    final ProductCommandDispatcher productCommandDispatcher = caseService.getProductCommandDispatcher(productIdentifier);
    productCommandDispatcher.dispatch(productIdentifier, caseIdentifier, command);

    return new ResponseEntity<>(HttpStatus.ACCEPTED);
  }

  private Case checkThatCaseExists(final String productIdentifier, final String caseIdentifier) {
    checkThatProductExists(productIdentifier);

    return caseService.findByIdentifier(productIdentifier, caseIdentifier)
            .orElseThrow(() -> ServiceException.notFound("Case with identifier " + productIdentifier + "." + caseIdentifier + " doesn't exist."));
  }

  private void checkThatProductExists(final String productIdentifier) {
    productService.findByIdentifier(productIdentifier)
            .orElseThrow(() -> ServiceException.notFound("Product with identifier " + productIdentifier + " doesn't exist."));
  }

  //TODO: check that case parameters are within product parameters in put and post.
}